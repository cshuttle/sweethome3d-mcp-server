package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class RenderSideViewHandlerTest {

    private RenderSideViewHandler handler;
    private HomeAccessor accessor;

    @BeforeEach
    void setUp() {
        handler = new RenderSideViewHandler();
        accessor = createAccessor(new Home());
    }

    private Response run(Map<String, Object> params) {
        return handler.execute(new Request("render_side_view", params), accessor);
    }

    // --- Parameter validation (all before any 3D work) ---

    @Test
    void testWidthOutOfRange() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("width", 5000.0);
        Response resp = run(params);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("width"));
    }

    @Test
    void testHeightOutOfRange() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("height", 2.0);
        Response resp = run(params);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("height"));
    }

    @Test
    void testUnknownSide() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("side", "north");
        Response resp = run(params);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("Unknown side"));
    }

    @Test
    void testSideAndYawTogether() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("side", "top");
        params.put("yaw", 45.0);
        Response resp = run(params);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not both"));
    }

    @Test
    void testLevelsMustBeArray() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("levels", "Ground");
        Response resp = run(params);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("levels"));
    }

    @Test
    void testLevelsMustNotBeEmpty() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("levels", Collections.emptyList());
        Response resp = run(params);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("at least one level"));
    }

    // --- Yaw resolution ---

    @Test
    void testDefaultSideIsBottom() {
        assertEquals(180f, RenderSideViewHandler.resolveYawDegrees(null, null));
    }

    @Test
    void testSidesMatchSetCameraYawConvention() {
        assertEquals(0f, RenderSideViewHandler.resolveYawDegrees("top", null));
        assertEquals(90f, RenderSideViewHandler.resolveYawDegrees("right", null));
        assertEquals(180f, RenderSideViewHandler.resolveYawDegrees("Bottom", null));
        assertEquals(270f, RenderSideViewHandler.resolveYawDegrees(" left ", null));
    }

    @Test
    void testYawIsFoldedIntoZeroTo360() {
        assertEquals(270f, RenderSideViewHandler.resolveYawDegrees(null, -90.0), 1e-4);
        assertEquals(45f, RenderSideViewHandler.resolveYawDegrees(null, 405.0), 1e-4);
    }

    @Test
    void testYawMustBeFiniteNumber() {
        assertThrows(IllegalArgumentException.class,
                () -> RenderSideViewHandler.resolveYawDegrees(null, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> RenderSideViewHandler.resolveYawDegrees(null, "90"));
    }

    // --- Geometry ---

    @Test
    void testRightVectorAtCardinalYaws() {
        // yaw 0 looks +y: right is -x. yaw 90 looks -x: right is -y.
        assertVector(-1, 0, RenderSideViewHandler.rightVector(0));
        assertVector(0, -1, RenderSideViewHandler.rightVector(90));
        assertVector(1, 0, RenderSideViewHandler.rightVector(180));
        assertVector(0, 1, RenderSideViewHandler.rightVector(270));
    }

    @Test
    void testPixelsPerCmFromPlanDiagonal() {
        // 3-4-5 plan extent: diagonal 500 cm, scale 240 cm, image spans 480 cm
        assertEquals(1000 / 480.0, RenderSideViewHandler.pixelsPerCm(1000, 300, 400), 1e-6);
    }

    @Test
    void testLookVectorAtCardinalYaws() {
        assertVector(0, 1, RenderSideViewHandler.lookVector(0));
        assertVector(-1, 0, RenderSideViewHandler.lookVector(90));
        assertVector(0, -1, RenderSideViewHandler.lookVector(180));
        assertVector(1, 0, RenderSideViewHandler.lookVector(270));
    }

    @Test
    void testElevationCameraStandsOutsideBoundsOnCentreLine() {
        // Bounds 300 x 400 centred on (100, 200); bottom side looks -y, so the camera stands at +y
        double[] camera = RenderSideViewHandler.cameraPosition(180, 100, 200, 300, 400, null);
        assertEquals(100, camera[0], 1e-9);
        assertEquals(200 + 250 + RenderSideViewHandler.ELEVATION_STANDOFF, camera[1], 1e-9);
    }

    @Test
    void testSectionCameraIsProjectedOntoCentreLine() {
        // Looking -y from the bottom: the cut plane is y = 150 whatever the point's x
        double[] camera = RenderSideViewHandler.cameraPosition(180, 100, 200, 300, 400, new double[] {999, 150});
        assertEquals(100, camera[0], 1e-9);
        assertEquals(150, camera[1], 1e-9);
    }

    @Test
    void testParseSectionAt() {
        assertNull(RenderSideViewHandler.parseSectionAt(null));
        double[] point = RenderSideViewHandler.parseSectionAt(Arrays.asList(10.0, -20.5));
        assertEquals(10.0, point[0]);
        assertEquals(-20.5, point[1]);
        assertThrows(IllegalArgumentException.class,
                () -> RenderSideViewHandler.parseSectionAt(Arrays.asList(1.0)));
        assertThrows(IllegalArgumentException.class,
                () -> RenderSideViewHandler.parseSectionAt(Arrays.asList(1.0, Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> RenderSideViewHandler.parseSectionAt("1,2"));
    }

    @Test
    void testInvalidSectionAtIsRejected() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("sectionAt", Arrays.asList(1.0, 2.0, 3.0));
        Response resp = run(params);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("sectionAt"));
    }

    @Test
    void testParseLevelRefsTrims() {
        List<String> refs = RenderSideViewHandler.parseLevelRefs(Arrays.asList(" Ground ", "level-upstairs"));
        assertEquals(Arrays.asList("Ground", "level-upstairs"), refs);
        assertNull(RenderSideViewHandler.parseLevelRefs(null));
    }

    // --- Descriptor ---

    @Test
    void testSchemaHasExpectedProperties() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) handler.getSchema().get("properties");
        for (String key : Arrays.asList("side", "yaw", "sectionAt", "levels", "width", "height", "filePath")) {
            assertTrue(props.containsKey(key), "missing schema property " + key);
        }
        assertFalse(handler.getDescription().isEmpty());
    }

    private static void assertVector(double x, double y, double[] actual) {
        assertEquals(x, actual[0], 1e-9);
        assertEquals(y, actual[1], 1e-9);
    }
}
