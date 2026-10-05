package com.sh3d.mcp.command.shape;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.UserPreferences;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.command.handler.GenerateShapeHandler;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * Checks the winding of the OBJ that generate_shape actually emits (house-model#69).
 *
 * <p>Sweet Home 3D culls back faces, so a face wound inward (its normal, by the
 * right-hand rule, pointing into the solid) is invisible from outside. Each test
 * generates a shape through {@link GenerateShapeHandler}, reads the OBJ back out of
 * the piece's model content, and checks every face's winding normal points out of
 * the solid: away from the centroid for a convex solid, and towards a point outside
 * the mesh (ray-parity test) for a concave one.</p>
 */
class ShapeWindingTest {

    // ======================== EXTRUDE ========================

    @Nested
    class Extrude {

        /** Positive shoelace area in plan coordinates (clockwise on screen, Y down). */
        private final List<List<Number>> rectPositive = pts(0, 0, 100, 0, 100, 50, 0, 50);
        private final List<List<Number>> triPositive = pts(0, 0, 100, 0, 50, 80);
        /** L-shaped (concave) footprint, like a stoop wrapping a corner. */
        private final List<List<Number>> lShapePositive = pts(0, 0, 200, 0, 200, 60, 60, 60, 60, 150, 0, 150);

        @Test
        void rectangleOutwardWhenPositiveOrientation() {
            assertConvexOutward(extrude(rectPositive, 30));
        }

        @Test
        void rectangleOutwardWhenNegativeOrientation() {
            assertConvexOutward(extrude(reversed(rectPositive), 30));
        }

        @Test
        void triangleOutwardInBothOrientations() {
            assertConvexOutward(extrude(triPositive, 45));
            assertConvexOutward(extrude(reversed(triPositive), 45));
        }

        @Test
        void concaveOutwardInBothOrientations() {
            assertOutwardByParity(extrude(lShapePositive, 20));
            assertOutwardByParity(extrude(reversed(lShapePositive), 20));
        }

        @Test
        void hasCapsAndAllSides() {
            Mesh mesh = extrude(rectPositive, 30);
            // 2 triangles per cap + 2 per side
            assertEquals(2 * 2 + 4 * 2, mesh.faces.size());
            int up = 0, down = 0;
            for (int[] f : mesh.faces) {
                double[] n = mesh.normal(f);
                if (n[1] > 0.99) up++;
                if (n[1] < -0.99) down++;
            }
            assertEquals(2, up, "top cap faces up");
            assertEquals(2, down, "bottom cap faces down");
        }
    }

    // ======================== OTHER PROCEDURAL MODES ========================

    @Nested
    class OtherModes {

        @Test
        void stairs() {
            Map<String, Object> p = params("stairs");
            p.put("width", 120.0);
            p.put("depth", 90.0);
            p.put("height", 54.0);
            p.put("steps", 3);
            assertOutwardByParity(generate(p));
        }

        @ParameterizedTest
        @ValueSource(strings = {"x", "y"})
        void wedge(String taper) {
            Map<String, Object> p = params("wedge");
            p.put("width", 100.0);
            p.put("depth", 60.0);
            p.put("height", 40.0);
            p.put("taper", taper);
            assertConvexOutward(generate(p));
        }

        @Test
        void box() {
            Map<String, Object> p = params("box");
            p.put("width", 100.0);
            p.put("depth", 60.0);
            p.put("height", 40.0);
            assertConvexOutward(generate(p));
        }

        @Test
        void cylinder() {
            Map<String, Object> p = params("cylinder");
            p.put("radius", 30.0);
            p.put("height", 80.0);
            assertConvexOutward(generate(p));
        }

        @Test
        void cone() {
            Map<String, Object> p = params("cone");
            p.put("radiusBottom", 30.0);
            p.put("height", 80.0);
            assertConvexOutward(generate(p));
        }

        @Test
        void sphere() {
            Map<String, Object> p = params("sphere");
            p.put("radius", 30.0);
            assertConvexOutward(generate(p));
        }

        @ParameterizedTest
        @ValueSource(doubles = {45.0, 90.0, 135.0})
        void hemisphere(double cutAngle) {
            Map<String, Object> p = params("hemisphere");
            p.put("radius", 30.0);
            p.put("cutAngle", cutAngle);
            assertConvexOutward(generate(p));
        }

        @Test
        void partialPipe() {
            Map<String, Object> p = params("pipe");
            p.put("outerRadius", 30.0);
            p.put("innerRadius", 20.0);
            p.put("height", 80.0);
            p.put("arcAngle", 120.0);
            assertOutwardByParity(generate(p));
        }

        @ParameterizedTest
        @ValueSource(strings = {"box", "sphere", "cylinder", "hemisphere"})
        void csgSubtract(String operandMode) {
            Map<String, Object> a = params("box");
            a.put("width", 100.0);
            a.put("depth", 100.0);
            a.put("height", 100.0);
            Map<String, Object> b = params(operandMode);
            b.put("width", 40.0);
            b.put("depth", 40.0);
            b.put("height", 200.0);
            b.put("radius", 30.0);
            b.put("offsetY", 0.0);
            Map<String, Object> p = params("csg");
            p.put("operation", "subtract");
            p.put("operandA", a);
            p.put("operandB", b);
            assertOutwardByParity(generate(p));
        }

        @ParameterizedTest
        @ValueSource(strings = {"sphere", "cylinder", "hemisphere"})
        void csgOperandAlone(String operandMode) {
            Map<String, Object> a = params(operandMode);
            a.put("radius", 30.0);
            a.put("height", 60.0);
            Map<String, Object> b = params("box");
            b.put("width", 10.0);
            b.put("depth", 10.0);
            b.put("height", 10.0);
            b.put("offsetX", 500.0);
            Map<String, Object> p = params("csg");
            p.put("operation", "union");
            p.put("operandA", a);
            p.put("operandB", b);
            assertOutwardByParity(generate(p));
        }

        @Test
        void arch() {
            Map<String, Object> p = params("arch");
            p.put("width", 120.0);
            p.put("height", 200.0);
            p.put("depth", 30.0);
            assertOutwardByParity(generate(p));
        }

        @Test
        void pipe() {
            Map<String, Object> p = params("pipe");
            p.put("outerRadius", 30.0);
            p.put("innerRadius", 20.0);
            p.put("height", 80.0);
            assertOutwardByParity(generate(p));
        }

        @Test
        void torus() {
            Map<String, Object> p = params("torus");
            p.put("majorRadius", 50.0);
            p.put("minorRadius", 10.0);
            assertOutwardByParity(generate(p));
        }
    }

    // ======================== HELPERS: GENERATION ========================

    private static Map<String, Object> params(String mode) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("mode", mode);
        return p;
    }

    private static List<List<Number>> pts(double... xy) {
        List<List<Number>> list = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            list.add(Arrays.<Number>asList(xy[i], xy[i + 1]));
        }
        return list;
    }

    private static List<List<Number>> reversed(List<List<Number>> points) {
        List<List<Number>> copy = new ArrayList<>(points);
        Collections.reverse(copy);
        return copy;
    }

    private static Mesh extrude(List<List<Number>> polygon, double height) {
        Map<String, Object> p = params("extrude");
        List<Object> poly = new ArrayList<>();
        for (List<Number> pt : polygon) {
            poly.add(new ArrayList<>(pt));
        }
        p.put("polygon", poly);
        p.put("height", height);
        return generate(p);
    }

    private static Mesh generate(Map<String, Object> params) {
        Home home = new Home();
        HomeAccessor accessor = new HomeAccessor(home, mock(UserPreferences.class));
        Response resp = new GenerateShapeHandler().execute(new Request("generate_shape", params), accessor);
        assertFalse(resp.isError(), () -> "generate_shape failed: " + resp.getMessage());
        assertEquals(1, home.getFurniture().size());
        HomePieceOfFurniture piece = home.getFurniture().get(0);
        try (InputStream in = piece.getModel().openStream()) {
            return Mesh.parseObj(in);
        } catch (IOException e) {
            throw new AssertionError("Cannot read the generated OBJ", e);
        }
    }

    // ======================== HELPERS: ASSERTIONS ========================

    /** Every face's winding normal points away from the solid's centroid (convex solids only). */
    private static void assertConvexOutward(Mesh mesh) {
        assertFalse(mesh.faces.isEmpty(), "mesh has no faces");
        double[] c = mesh.centroid();
        int checked = 0;
        for (int[] f : mesh.faces) {
            double[] n = mesh.normal(f);
            if (n == null) continue; // degenerate (zero-area) face
            double[] fc = mesh.faceCentroid(f);
            double dot = n[0] * (fc[0] - c[0]) + n[1] * (fc[1] - c[1]) + n[2] * (fc[2] - c[2]);
            if (dot <= 0) {
                fail("Face " + Arrays.toString(f) + " at " + Arrays.toString(fc)
                        + " has normal " + Arrays.toString(n) + " pointing towards the centroid "
                        + Arrays.toString(c));
            }
            checked++;
        }
        assertTrue(checked > 0, "no non-degenerate faces");
    }

    /**
     * Every face's winding normal points out of the solid: a point just in front of the
     * face is outside the closed mesh and a point just behind it is inside, judged by the
     * parity of ray crossings. Works for concave and hollow solids.
     */
    private static void assertOutwardByParity(Mesh mesh) {
        assertFalse(mesh.faces.isEmpty(), "mesh has no faces");
        double eps = mesh.diagonal() * 1e-4;
        int checked = 0;
        for (int[] f : mesh.faces) {
            double[] n = mesh.normal(f);
            if (n == null) continue;
            double[] fc = mesh.faceCentroid(f);
            double[] front = {fc[0] + eps * n[0], fc[1] + eps * n[1], fc[2] + eps * n[2]};
            double[] back = {fc[0] - eps * n[0], fc[1] - eps * n[1], fc[2] - eps * n[2]};
            boolean frontInside = mesh.contains(front);
            boolean backInside = mesh.contains(back);
            if (frontInside || !backInside) {
                fail("Face " + Arrays.toString(f) + " at " + Arrays.toString(fc)
                        + " with normal " + Arrays.toString(n) + " is wound inward"
                        + " (front inside=" + frontInside + ", back inside=" + backInside + ")");
            }
            checked++;
        }
        assertTrue(checked > 0, "no non-degenerate faces");
    }

    // ======================== HELPERS: OBJ MESH ========================

    /** Minimal OBJ reader: positions and faces (any polygon size, global indices). */
    static final class Mesh {
        final List<double[]> vertices = new ArrayList<>();
        final List<int[]> faces = new ArrayList<>();

        static Mesh parseObj(InputStream in) throws IOException {
            Mesh mesh = new Mesh();
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] tok = line.trim().split("\\s+");
                if (tok[0].equals("v")) {
                    mesh.vertices.add(new double[]{
                            Double.parseDouble(tok[1]), Double.parseDouble(tok[2]), Double.parseDouble(tok[3])});
                } else if (tok[0].equals("f")) {
                    int[] face = new int[tok.length - 1];
                    for (int i = 1; i < tok.length; i++) {
                        int idx = Integer.parseInt(tok[i].split("/")[0]);
                        face[i - 1] = idx > 0 ? idx - 1 : mesh.vertices.size() + idx;
                    }
                    mesh.faces.add(face);
                }
            }
            return mesh;
        }

        double[] centroid() {
            double[] c = new double[3];
            for (double[] v : vertices) {
                c[0] += v[0]; c[1] += v[1]; c[2] += v[2];
            }
            int n = vertices.size();
            return new double[]{c[0] / n, c[1] / n, c[2] / n};
        }

        double diagonal() {
            double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
            double[] max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (double[] v : vertices) {
                for (int k = 0; k < 3; k++) {
                    min[k] = Math.min(min[k], v[k]);
                    max[k] = Math.max(max[k], v[k]);
                }
            }
            double dx = max[0] - min[0], dy = max[1] - min[1], dz = max[2] - min[2];
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        double[] faceCentroid(int[] f) {
            double[] c = new double[3];
            for (int i : f) {
                double[] v = vertices.get(i);
                c[0] += v[0]; c[1] += v[1]; c[2] += v[2];
            }
            return new double[]{c[0] / f.length, c[1] / f.length, c[2] / f.length};
        }

        /** Unit normal by the right-hand rule (Newell's method); null if degenerate. */
        double[] normal(int[] f) {
            double nx = 0, ny = 0, nz = 0;
            for (int i = 0; i < f.length; i++) {
                double[] a = vertices.get(f[i]);
                double[] b = vertices.get(f[(i + 1) % f.length]);
                nx += (a[1] - b[1]) * (a[2] + b[2]);
                ny += (a[2] - b[2]) * (a[0] + b[0]);
                nz += (a[0] - b[0]) * (a[1] + b[1]);
            }
            double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-9) return null;
            return new double[]{nx / len, ny / len, nz / len};
        }

        /** Point-in-mesh by ray-crossing parity along a direction unlikely to graze an edge. */
        boolean contains(double[] p) {
            double[] dir = {0.5773, 0.6215, 0.5297};
            int crossings = 0;
            for (int[] f : faces) {
                for (int i = 1; i + 1 < f.length; i++) {
                    if (rayHitsTriangle(p, dir, vertices.get(f[0]), vertices.get(f[i]), vertices.get(f[i + 1]))) {
                        crossings++;
                    }
                }
            }
            return crossings % 2 == 1;
        }

        /** Möller–Trumbore, counting hits strictly in front of the origin. */
        private static boolean rayHitsTriangle(double[] o, double[] d, double[] a, double[] b, double[] c) {
            double[] e1 = {b[0] - a[0], b[1] - a[1], b[2] - a[2]};
            double[] e2 = {c[0] - a[0], c[1] - a[1], c[2] - a[2]};
            double[] h = cross(d, e2);
            double det = dot(e1, h);
            if (Math.abs(det) < 1e-12) return false;
            double inv = 1.0 / det;
            double[] s = {o[0] - a[0], o[1] - a[1], o[2] - a[2]};
            double u = inv * dot(s, h);
            if (u < 0 || u > 1) return false;
            double[] q = cross(s, e1);
            double v = inv * dot(d, q);
            if (v < 0 || u + v > 1) return false;
            return inv * dot(e2, q) > 1e-9;
        }

        private static double[] cross(double[] a, double[] b) {
            return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
        }

        private static double dot(double[] a, double[] b) {
            return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
        }
    }
}
