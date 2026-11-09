package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.io.HomeXMLExporter;
import com.eteks.sweethome3d.io.XMLWriter;
import com.sh3d.mcp.bridge.CheckpointManager;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class ModifyFurnitureHandlerTest {

    private ModifyFurnitureHandler handler;
    private HomeAccessor accessor;
    private Home home;

    @BeforeEach
    void setUp() {
        handler = new ModifyFurnitureHandler();
        home = new Home();
        accessor = createAccessor(home);
    }

    @Test
    void testModifyPosition() {
        HomePieceOfFurniture piece = addFurniture("Table", 100, 200);

        Response resp = handler.execute(makeRequest(piece.getId(), "x", 300.0, "y", 400.0), accessor);

        assertTrue(resp.isOk());
        assertEquals(300f, piece.getX(), 0.01f);
        assertEquals(400f, piece.getY(), 0.01f);

        assertEquals(300.0, ((Number) resp.getData().get("x")).doubleValue(), 0.01);
        assertEquals(400.0, ((Number) resp.getData().get("y")).doubleValue(), 0.01);
    }

    @Test
    void testModifyAngle() {
        HomePieceOfFurniture piece = addFurniture("Chair", 0, 0);

        Response resp = handler.execute(makeRequest(piece.getId(), "angle", 90.0), accessor);

        assertTrue(resp.isOk());
        assertEquals(Math.toRadians(90), piece.getAngle(), 0.01);

        assertEquals(90.0, ((Number) resp.getData().get("angle")).doubleValue(), 0.5);
    }

    @Test
    void testModifyDimensions() {
        HomePieceOfFurniture piece = addFurniture("Box", 0, 0);

        Response resp = handler.execute(makeRequest(piece.getId(), "width", 120.0, "depth", 80.0, "height", 75.0), accessor);

        assertTrue(resp.isOk());
        assertEquals(120f, piece.getWidth(), 0.01f);
        assertEquals(80f, piece.getDepth(), 0.01f);
        assertEquals(75f, piece.getHeight(), 0.01f);
    }

    @Test
    void testModifyElevation() {
        HomePieceOfFurniture piece = addFurniture("Shelf", 0, 0);

        Response resp = handler.execute(makeRequest(piece.getId(), "elevation", 150.0), accessor);

        assertTrue(resp.isOk());
        assertEquals(150f, piece.getElevation(), 0.01f);
        assertEquals(150.0, ((Number) resp.getData().get("elevation")).doubleValue(), 0.01);
    }

    @Test
    void testSetColor() {
        HomePieceOfFurniture piece = addFurniture("Wall unit", 0, 0);

        Response resp = handler.execute(makeRequest(piece.getId(), "color", "#FF0000"), accessor);

        assertTrue(resp.isOk());
        assertEquals(0xFF0000, (int) piece.getColor());
        assertEquals("#FF0000", resp.getData().get("color"));
    }

    @Test
    void testResetColor() {
        HomePieceOfFurniture piece = addFurniture("Desk", 0, 0);
        piece.setColor(0x00FF00);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", piece.getId());
        params.put("color", null);
        Response resp = handler.execute(new Request("modify_furniture", params), accessor);

        assertTrue(resp.isOk());
        assertNull(piece.getColor());
        assertNull(resp.getData().get("color"));
    }

    @Test
    void testInvalidColorFormat() {
        HomePieceOfFurniture piece = addFurniture("Chair", 0, 0);

        Response resp = handler.execute(makeRequest(piece.getId(), "color", "red"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("color"));
    }

    @Test
    void testSetVisible() {
        HomePieceOfFurniture piece = addFurniture("Lamp", 0, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", piece.getId());
        params.put("visible", false);
        Response resp = handler.execute(new Request("modify_furniture", params), accessor);

        assertTrue(resp.isOk());
        assertFalse(piece.isVisible());
        assertEquals(false, resp.getData().get("visible"));
    }

    @Test
    void testSetMirrored() {
        HomePieceOfFurniture piece = addFurniture("Sofa", 0, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", piece.getId());
        params.put("mirrored", true);
        Response resp = handler.execute(new Request("modify_furniture", params), accessor);

        assertTrue(resp.isOk());
        assertTrue(piece.isModelMirrored());
        assertEquals(true, resp.getData().get("mirrored"));
    }

    @Test
    void testModifyName() {
        HomePieceOfFurniture piece = addFurniture("Old Name", 0, 0);

        Response resp = handler.execute(makeRequest(piece.getId(), "name", "New Name"), accessor);

        assertTrue(resp.isOk());
        assertEquals("New Name", piece.getName());
        assertEquals("New Name", resp.getData().get("name"));
    }

    @Test
    void testPartialUpdateOnlyX() {
        HomePieceOfFurniture piece = addFurniture("Table", 100, 200);
        piece.setAngle((float) Math.toRadians(45));

        Response resp = handler.execute(makeRequest(piece.getId(), "x", 500.0), accessor);

        assertTrue(resp.isOk());
        assertEquals(500f, piece.getX(), 0.01f);
        assertEquals(200f, piece.getY(), 0.01f);
        assertEquals(Math.toRadians(45), piece.getAngle(), 0.01);
    }

    @Test
    void testMultipleProperties() {
        HomePieceOfFurniture piece = addFurniture("Chair", 0, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", piece.getId());
        params.put("x", 250.0);
        params.put("y", 350.0);
        params.put("angle", 180.0);
        params.put("elevation", 10.0);
        params.put("visible", true);
        Response resp = handler.execute(new Request("modify_furniture", params), accessor);

        assertTrue(resp.isOk());
        assertEquals(250f, piece.getX(), 0.01f);
        assertEquals(350f, piece.getY(), 0.01f);
        assertEquals(Math.toRadians(180), piece.getAngle(), 0.01);
        assertEquals(10f, piece.getElevation(), 0.01f);
        assertTrue(piece.isVisible());
    }

    @Test
    void testIdNotFound() {
        addFurniture("Table", 0, 0);

        Response resp = handler.execute(makeRequest("nonexistent-id", "x", 100.0), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testNoModifiableProperties() {
        HomePieceOfFurniture piece = addFurniture("Table", 0, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", piece.getId());
        Response resp = handler.execute(new Request("modify_furniture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("No modifiable properties"));
    }

    @Test
    void testEmptyScene() {
        Response resp = handler.execute(makeRequest("any-id", "x", 100.0), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testResponseContainsAllFields() {
        HomePieceOfFurniture piece = addFurniture("Table", 100, 200);

        Response resp = handler.execute(makeRequest(piece.getId(), "x", 300.0), accessor);

        assertTrue(resp.isOk());
        Map<String, Object> data = resp.getData();
        assertEquals(piece.getId(), data.get("id"));
        assertNotNull(data.get("name"));
        assertNotNull(data.get("x"));
        assertNotNull(data.get("y"));
        assertNotNull(data.get("angle"));
        assertNotNull(data.get("elevation"));
        assertNotNull(data.get("width"));
        assertNotNull(data.get("depth"));
        assertNotNull(data.get("height"));
        assertTrue(data.containsKey("color"));
        assertTrue(data.containsKey("visible"));
        assertTrue(data.containsKey("mirrored"));
    }

    @Test
    void testDescriptorFields() {
        assertNotNull(handler.getDescription());
        assertFalse(handler.getDescription().isEmpty());

        Map<String, Object> schema = handler.getSchema();
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("id"));

        @SuppressWarnings("unchecked")
        Map<String, Object> idProp = (Map<String, Object>) props.get("id");
        assertEquals("string", idProp.get("type"));

        assertTrue(props.containsKey("x"));
        assertTrue(props.containsKey("y"));
        assertTrue(props.containsKey("angle"));
        assertTrue(props.containsKey("color"));
        assertTrue(props.containsKey("visible"));
        assertTrue(props.containsKey("mirrored"));
        assertTrue(props.containsKey("name"));
        assertTrue(props.containsKey("dropOnTopElevation"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("id"));
        assertEquals(1, required.size());
    }

    // --- Custom properties ---

    @Test
    void testSetProperty() {
        HomePieceOfFurniture piece = addFurniture("Porch light", 0, 0);

        Response resp = handler.execute(makeRequest(piece.getId(),
                "properties", props("ha_entity", "light.porch,light.porch_2")), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals("light.porch,light.porch_2", piece.getProperty("ha_entity"));
        assertEquals(props("ha_entity", "light.porch,light.porch_2"), resp.getData().get("properties"));
    }

    @Test
    void testPropertiesAloneAreAModification() {
        HomePieceOfFurniture piece = addFurniture("Lamp", 10, 20);

        Response resp = handler.execute(makeRequest(piece.getId(), "properties", props("ha_entity", "light.lamp")),
                accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(10f, piece.getX(), 0.01f);
        assertEquals("Lamp", piece.getName());
    }

    @Test
    void testNullRemovesAPropertyAndKeepsTheOthers() {
        HomePieceOfFurniture piece = addFurniture("Switch", 0, 0);
        piece.setProperty("ha_entity", "switch.a");
        piece.setProperty("note", "keep me");
        piece.setProperty("old", "drop me");

        Response resp = handler.execute(makeRequest(piece.getId(),
                "properties", props("ha_entity", "switch.b", "old", null)), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals("switch.b", piece.getProperty("ha_entity"));
        assertEquals("keep me", piece.getProperty("note"));
        assertNull(piece.getProperty("old"));
        assertEquals(Arrays.asList("ha_entity", "note"),
                Arrays.asList(((Map<?, ?>) resp.getData().get("properties")).keySet().toArray()));
    }

    @Test
    void testRemovingTheLastPropertyReportsNone() {
        HomePieceOfFurniture piece = addFurniture("Switch", 0, 0);
        piece.setProperty("ha_entity", "switch.a");

        Response resp = handler.execute(makeRequest(piece.getId(), "properties", props("ha_entity", null)), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertTrue(piece.getPropertyNames().isEmpty());
        assertNull(resp.getData().get("properties"));
    }

    @Test
    void testInvalidPropertiesAreRejectedBeforeAnyChange() {
        HomePieceOfFurniture piece = addFurniture("Lamp", 0, 0);

        assertTrue(handler.execute(makeRequest(piece.getId(), "x", 99.0, "properties", "ha_entity=light.a"),
                accessor).isError());
        assertTrue(handler.execute(makeRequest(piece.getId(), "properties", props("ha_entity", 5)),
                accessor).isError());
        assertTrue(handler.execute(makeRequest(piece.getId(), "properties", props(" ", "light.a")),
                accessor).isError());
        assertTrue(handler.execute(makeRequest(piece.getId(), "properties", Collections.emptyMap()),
                accessor).isError());

        assertEquals(0f, piece.getX(), 0.01f);
        assertTrue(piece.getPropertyNames().isEmpty());
    }

    @Test
    void testPropertyChangeIsUndoneByRestoreCheckpoint() {
        HomePieceOfFurniture piece = addFurniture("Lamp", 0, 0);
        CheckpointManager checkpoints = new CheckpointManager();
        CheckpointHandler checkpoint = new CheckpointHandler(checkpoints);
        checkpoint.execute(new Request("checkpoint", Collections.emptyMap()), accessor);

        handler.execute(makeRequest(piece.getId(), "properties", props("ha_entity", "light.lamp")), accessor);
        checkpoint.execute(new Request("checkpoint", Collections.emptyMap()), accessor);
        Response restored = new RestoreCheckpointHandler(checkpoints)
                .execute(new Request("restore_checkpoint", Collections.emptyMap()), accessor);

        assertTrue(restored.isOk(), restored.getMessage());
        HomePieceOfFurniture after = home.getFurniture().get(0);
        assertEquals(piece.getId(), after.getId());
        assertNull(after.getProperty("ha_entity"));
    }

    @Test
    void testPropertyIsSavedInHomeXml() throws Exception {
        HomePieceOfFurniture piece = addFurniture("Lamp", 0, 0);
        handler.execute(makeRequest(piece.getId(), "properties", props("ha_entity", "light.a,light.b")), accessor);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLWriter writer = new XMLWriter(out);
        new HomeXMLExporter().writeElement(writer, home);
        writer.flush();
        String xml = new String(out.toByteArray(), StandardCharsets.UTF_8);

        int pieceStart = xml.indexOf("<pieceOfFurniture ");
        int pieceEnd = xml.indexOf("</pieceOfFurniture>", pieceStart);
        assertTrue(pieceStart >= 0 && pieceEnd > pieceStart, xml);
        assertTrue(xml.substring(pieceStart, pieceEnd).contains("<property name='ha_entity' value='light.a,light.b'/>"),
                xml);
    }

    @Test
    void testSchemaDescribesProperties() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) handler.getSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> def = (Map<String, Object>) props.get("properties");
        assertEquals("object", def.get("type"));
        assertNotNull(def.get("additionalProperties"));
    }

    private static Map<String, Object> props(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    @Test
    void testModifyDropOnTopElevation() {
        HomePieceOfFurniture piece = addFurniture("Basement walls", 0, 0);
        assertEquals(1f, piece.getDropOnTopElevation(), 0.001f);

        Response resp = handler.execute(makeRequest(piece.getId(), "dropOnTopElevation", -1.0), accessor);

        assertTrue(resp.isOk());
        assertEquals(-1f, piece.getDropOnTopElevation(), 0.001f);
        assertEquals(-1.0, ((Number) resp.getData().get("dropOnTopElevation")).doubleValue(), 0.001);
        assertEquals(0f, piece.getX(), 0.01f);
    }

    @Test
    void testDropOnTopElevationSurvivesClone() {
        HomePieceOfFurniture piece = addFurniture("Trim", 0, 0);
        handler.execute(makeRequest(piece.getId(), "dropOnTopElevation", 0.5), accessor);

        assertEquals(0.5f, piece.clone().getDropOnTopElevation(), 0.001f);
    }

    @Test
    void testDropOnTopElevationMustBeANumber() {
        HomePieceOfFurniture piece = addFurniture("Trim", 0, 0);

        Response resp = handler.execute(makeRequest(piece.getId(), "dropOnTopElevation", "none"), accessor);

        assertTrue(resp.isError());
        assertEquals(1f, piece.getDropOnTopElevation(), 0.001f);
    }

    @Test
    void testNegativeDropOnTopElevationOnAGroupSwitchesOffEveryPiece() {
        HomePieceOfFurniture a = piece("Foundation", 0, 0), b = piece("Foundation", 100, 0), c = piece("Foundation", 0, 100);
        HomeFurnitureGroup inner = new HomeFurnitureGroup(Arrays.asList(b, c), "Inner");
        HomeFurnitureGroup group = new HomeFurnitureGroup(Arrays.asList(a, inner), "Foundation (above grade)");
        home.addPieceOfFurniture(group);
        assertTrue(group.getDropOnTopElevation() > 0);

        Response resp = handler.execute(makeRequest(group.getId(), "dropOnTopElevation", -1.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        for (HomePieceOfFurniture p : Arrays.asList(a, b, c, inner, group)) {
            assertTrue(p.getDropOnTopElevation() < 0, p.getName());
        }
        assertEquals(-1.0, ((Number) resp.getData().get("dropOnTopElevation")).doubleValue(), 1e-6);
        // as the home is loaded: the group's value is computed again from its pieces, and stays negative
        HomeFurnitureGroup reloaded = new HomeFurnitureGroup(Arrays.asList(a.clone(), new HomeFurnitureGroup(
                Arrays.asList(b.clone(), c.clone()), "Inner")), "Foundation (above grade)");
        assertTrue(reloaded.getDropOnTopElevation() < 0);
    }

    @Test
    void testNonNegativeDropOnTopElevationOnAGroupIsRefused() {
        HomePieceOfFurniture a = piece("Chair", 0, 0);
        HomeFurnitureGroup group = new HomeFurnitureGroup(Collections.singletonList(a), "Chairs");
        home.addPieceOfFurniture(group);
        float before = group.getDropOnTopElevation();

        Response resp = handler.execute(makeRequest(group.getId(), "dropOnTopElevation", 0.5, "x", 300.0), accessor);

        assertTrue(resp.isError());
        assertEquals(before, group.getDropOnTopElevation(), 1e-6);
        assertEquals(1f, a.getDropOnTopElevation(), 1e-6);
        assertNotEquals(300f, group.getX(), 0.01f);
    }

    private static HomePieceOfFurniture piece(String name, float x, float y) {
        HomePieceOfFurniture p = new HomePieceOfFurniture(
                new CatalogPieceOfFurniture(name, null, null, 50f, 50f, 50f, true, false));
        p.setX(x);
        p.setY(y);
        return p;
    }

    private HomePieceOfFurniture addFurniture(String name, float x, float y) {
        HomePieceOfFurniture piece = new HomePieceOfFurniture(
                new CatalogPieceOfFurniture(
                        name, null, null, 50f, 50f, 50f, true, false));
        piece.setX(x);
        piece.setY(y);
        home.addPieceOfFurniture(piece);
        return piece;
    }

    private Request makeRequest(String id, Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Request("modify_furniture", params);
    }
}
