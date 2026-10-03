package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.j3d.ModelManager;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.tools.URLContent;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class ImportFurnitureHandlerTest {

    /** Fixture: a 100 x 50 x 80 cm box (x, z, y) with a red material and a textured top. */
    static String fixture() throws Exception {
        return Paths.get(ImportFurnitureHandlerTest.class.getResource("/models/two_tone.obj").toURI()).toString();
    }

    private ImportFurnitureHandler handler;
    private HomeAccessor accessor;
    private Home home;

    @BeforeEach
    void setUp() {
        handler = new ImportFurnitureHandler();
        home = new Home();
        accessor = createAccessor(home);
    }

    // ==================== Validation ====================

    @Test
    void testMissingFilePath() {
        Response resp = handler.execute(request("name", "Box", "x", 0.0, "y", 0.0), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("filePath"));
    }

    @Test
    void testMissingName() throws Exception {
        Response resp = handler.execute(request("filePath", fixture(), "x", 0.0, "y", 0.0), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("name"));
    }

    @Test
    void testMissingY() throws Exception {
        Response resp = handler.execute(request("filePath", fixture(), "name", "Box", "x", 0.0), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("y"));
    }

    @Test
    void testNonPositiveSize() throws Exception {
        Response resp = handler.execute(
                request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0, "width", 0.0), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("positive"));
    }

    @Test
    void testBadModelRotation() throws Exception {
        Response resp = handler.execute(
                request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0, "modelRotation", "xUp"), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("modelRotation"));
    }

    @Test
    void testFileNotFound() {
        Response resp = handler.execute(
                request("filePath", "/nonexistent/model.obj", "name", "Box", "x", 0.0, "y", 0.0), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
        assertTrue(home.getFurniture().isEmpty());
    }

    @Test
    void testNotAModel(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("junk.obj");
        Files.write(junk, "this is not a model".getBytes("UTF-8"));
        Response resp = handler.execute(
                request("filePath", junk.toString(), "name", "Junk", "x", 0.0, "y", 0.0), accessor);
        assertTrue(resp.isError());
        assertTrue(home.getFurniture().isEmpty());
    }

    @Test
    void testUnknownLevel() throws Exception {
        Response resp = handler.execute(
                request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0, "level", "Attic"), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("Level not found"));
    }

    // ==================== Import ====================

    @Test
    void testNaturalSizeAndPlacement() throws Exception {
        Response resp = handler.execute(request("filePath", fixture(), "name", "Box",
                "x", 120.0, "y", 340.0, "elevation", 10.0, "angle", 90.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomePieceOfFurniture piece = home.getFurniture().get(0);
        assertEquals(resp.getData().get("id"), piece.getId());
        assertEquals("Box", piece.getName());
        assertEquals(100f, piece.getWidth(), 0.01f);
        assertEquals(50f, piece.getDepth(), 0.01f);
        assertEquals(80f, piece.getHeight(), 0.01f);
        assertEquals(120f, piece.getX(), 0.01f);
        assertEquals(340f, piece.getY(), 0.01f);
        assertEquals(10f, piece.getElevation(), 0.01f);
        assertEquals(Math.toRadians(90), piece.getAngle(), 0.01);
        assertFalse(piece.isMovable());
        assertEquals(100.0, ((Number) resp.getData().get("width")).doubleValue(), 0.01);
    }

    @Test
    void testModelIsCopiedWithMaterialsAndTexture() throws Exception {
        Response resp = handler.execute(request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0), accessor);
        assertTrue(resp.isOk(), resp.getMessage());

        URLContent model = (URLContent) home.getFurniture().get(0).getModel();
        assertTrue(model.isJAREntry());
        assertEquals("two_tone.obj", model.getJAREntryName());
        File zip = new File(URI.create(model.getJAREntryURL().toString()));
        assertNotEquals(new File(fixture()).getParentFile(), zip.getParentFile());
        List<String> entries;
        try (ZipFile zipFile = new ZipFile(zip)) {
            entries = zipFile.stream().map(ZipEntry::getName).collect(Collectors.toList());
        }
        assertTrue(entries.contains("two_tone.obj"), entries.toString());
        assertTrue(entries.contains("two_tone.mtl"), entries.toString());
        assertTrue(entries.stream().anyMatch(e -> e.endsWith(".png") || e.endsWith(".jpg")), entries.toString());

        HomeMaterial[] materials = ModelManager.getInstance().getMaterials(ModelManager.getInstance().loadModel(model));
        List<String> names = Arrays.stream(materials).map(HomeMaterial::getName).collect(Collectors.toList());
        assertTrue(names.contains("paint"), names.toString());
        assertTrue(names.contains("label"), names.toString());
        assertTrue(Arrays.stream(materials).anyMatch(m -> m.getTexture() != null), "texture lost");
    }

    @Test
    void testEachImportGetsANewModelUrl() throws Exception {
        handler.execute(request("filePath", fixture(), "name", "A", "x", 0.0, "y", 0.0), accessor);
        handler.execute(request("filePath", fixture(), "name", "B", "x", 0.0, "y", 0.0), accessor);

        assertNotEquals(home.getFurniture().get(0).getModel(), home.getFurniture().get(1).getModel());
    }

    @Test
    void testOneDimensionKeepsProportions() throws Exception {
        Response resp = handler.execute(
                request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0, "width", 50.0), accessor);
        assertTrue(resp.isOk(), resp.getMessage());
        HomePieceOfFurniture piece = home.getFurniture().get(0);
        assertEquals(50f, piece.getWidth(), 0.01f);
        assertEquals(25f, piece.getDepth(), 0.01f);
        assertEquals(40f, piece.getHeight(), 0.01f);
    }

    @Test
    void testAllDimensionsGiven() throws Exception {
        Response resp = handler.execute(request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0,
                "width", 10.0, "depth", 20.0, "height", 30.0), accessor);
        assertTrue(resp.isOk(), resp.getMessage());
        HomePieceOfFurniture piece = home.getFurniture().get(0);
        assertEquals(10f, piece.getWidth(), 0.01f);
        assertEquals(20f, piece.getDepth(), 0.01f);
        assertEquals(30f, piece.getHeight(), 0.01f);
    }

    @Test
    void testZUpSwapsDepthAndHeight() throws Exception {
        Response resp = handler.execute(request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0,
                "modelRotation", "zUp"), accessor);
        assertTrue(resp.isOk(), resp.getMessage());
        HomePieceOfFurniture piece = home.getFurniture().get(0);
        assertEquals(100f, piece.getWidth(), 0.01f);
        assertEquals(80f, piece.getDepth(), 0.01f);
        assertEquals(50f, piece.getHeight(), 0.01f);
        assertArrayEquals(new float[] {0, -1, 0}, piece.getModelRotation()[2]);
    }

    @Test
    void testMatrixRotation() throws Exception {
        List<List<Double>> matrix = Arrays.asList(
                Arrays.asList(0.0, 0.0, 1.0), Arrays.asList(0.0, 1.0, 0.0), Arrays.asList(-1.0, 0.0, 0.0));
        Response resp = handler.execute(request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0,
                "modelRotation", matrix), accessor);
        assertTrue(resp.isOk(), resp.getMessage());
        HomePieceOfFurniture piece = home.getFurniture().get(0);
        assertEquals(50f, piece.getWidth(), 0.01f);
        assertEquals(100f, piece.getDepth(), 0.01f);
    }

    @Test
    void testLevelByName() throws Exception {
        Level ground = new Level("Ground", 0, 12, 250);
        Level upper = new Level("Upper", 250, 12, 250);
        home.addLevel(ground);
        home.addLevel(upper);
        home.setSelectedLevel(ground);

        Response resp = handler.execute(request("filePath", fixture(), "name", "Box", "x", 0.0, "y", 0.0,
                "level", "upper", "movable", true), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertSame(upper, home.getFurniture().get(0).getLevel());
        assertEquals("Upper", resp.getData().get("level"));
        assertTrue(home.getFurniture().get(0).isMovable());
    }

    @Test
    void testZipHoldingObj(@TempDir Path dir) throws Exception {
        File zip = dir.resolve("box.zip").toFile();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (String name : new String[] {"two_tone.obj", "two_tone.mtl", "two_tone.png"}) {
                out.putNextEntry(new ZipEntry("box/" + name));
                out.write(Files.readAllBytes(Paths.get(fixture()).resolveSibling(name)));
                out.closeEntry();
            }
        }
        Response resp = handler.execute(
                request("filePath", zip.toString(), "name", "Zipped", "x", 0.0, "y", 0.0), accessor);
        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(100f, home.getFurniture().get(0).getWidth(), 0.01f);
    }

    // ==================== Descriptor ====================

    @Test
    void testSchema() {
        Map<String, Object> schema = handler.getSchema();
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.containsAll(Arrays.asList("filePath", "name", "x", "y")));
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("modelRotation"));
        assertTrue(props.containsKey("level"));
    }

    static Request request(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Request("import_furniture", params);
    }
}
