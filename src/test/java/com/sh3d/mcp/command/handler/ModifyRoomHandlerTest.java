package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.sh3d.mcp.bridge.CheckpointManager;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class ModifyRoomHandlerTest {

    private ModifyRoomHandler handler;
    private HomeAccessor accessor;
    private Home home;

    @BeforeEach
    void setUp() {
        handler = new ModifyRoomHandler();
        home = new Home();
        accessor = createAccessor(home);
    }

    // --- Name ---

    @Test
    void testSetName() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "name", "Kitchen"), accessor);

        assertTrue(resp.isOk());
        assertEquals("Kitchen", room.getName());
        assertEquals("Kitchen", resp.getData().get("name"));
    }

    @Test
    void testSetNameToNull() {
        Room room = addRoom();
        room.setName("Old Name");

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", room.getId());
        params.put("name", null);
        Response resp = handler.execute(new Request("modify_room", params), accessor);

        assertTrue(resp.isOk());
        assertNull(room.getName());
    }

    // --- Visibility ---

    @Test
    void testSetFloorVisible() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "floorVisible", false), accessor);

        assertTrue(resp.isOk());
        assertFalse(room.isFloorVisible());
        assertEquals(false, resp.getData().get("floorVisible"));
    }

    @Test
    void testSetCeilingVisible() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "ceilingVisible", false), accessor);

        assertTrue(resp.isOk());
        assertFalse(room.isCeilingVisible());
        assertEquals(false, resp.getData().get("ceilingVisible"));
    }

    @Test
    void testSetAreaVisible() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "areaVisible", true), accessor);

        assertTrue(resp.isOk());
        assertTrue(room.isAreaVisible());
        assertEquals(true, resp.getData().get("areaVisible"));
    }

    // --- Colors ---

    @Test
    void testSetFloorColor() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "floorColor", "#CCBB99"), accessor);

        assertTrue(resp.isOk());
        assertEquals(0xCCBB99, (int) room.getFloorColor());
        assertEquals("#CCBB99", resp.getData().get("floorColor"));
    }

    @Test
    void testSetCeilingColor() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "ceilingColor", "#FFFFFF"), accessor);

        assertTrue(resp.isOk());
        assertEquals(0xFFFFFF, (int) room.getCeilingColor());
        assertEquals("#FFFFFF", resp.getData().get("ceilingColor"));
    }

    @Test
    void testClearFloorColor() {
        Room room = addRoom();
        room.setFloorColor(0xFF0000);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", room.getId());
        params.put("floorColor", null);
        Response resp = handler.execute(new Request("modify_room", params), accessor);

        assertTrue(resp.isOk());
        assertNull(room.getFloorColor());
        assertNull(resp.getData().get("floorColor"));
    }

    @Test
    void testClearCeilingColor() {
        Room room = addRoom();
        room.setCeilingColor(0x00FF00);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", room.getId());
        params.put("ceilingColor", null);
        Response resp = handler.execute(new Request("modify_room", params), accessor);

        assertTrue(resp.isOk());
        assertNull(room.getCeilingColor());
        assertNull(resp.getData().get("ceilingColor"));
    }

    @Test
    void testInvalidFloorColorFormat() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "floorColor", "red"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("floorColor"));
    }

    @Test
    void testInvalidCeilingColorFormat() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "ceilingColor", "#GGG"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("ceilingColor"));
    }

    // --- Shininess ---

    @Test
    void testSetFloorShininess() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "floorShininess", 0.5), accessor);

        assertTrue(resp.isOk());
        assertEquals(0.5f, room.getFloorShininess(), 0.01f);
        assertEquals(0.5, ((Number) resp.getData().get("floorShininess")).doubleValue(), 0.01);
    }

    @Test
    void testSetCeilingShininess() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "ceilingShininess", 0.8), accessor);

        assertTrue(resp.isOk());
        assertEquals(0.8f, room.getCeilingShininess(), 0.01f);
        assertEquals(0.8, ((Number) resp.getData().get("ceilingShininess")).doubleValue(), 0.01);
    }

    @Test
    void testFloorShininessOutOfRange() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "floorShininess", 1.5), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("floorShininess"));
        assertTrue(resp.getMessage().contains("0.0"));
    }

    @Test
    void testCeilingShininessNegative() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "ceilingShininess", -0.1), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("ceilingShininess"));
    }

    // --- Points (geometry) ---

    @Test
    void testReplacePointsInPlaceKeepsIdentityAndAppearance() {
        Level level = new Level("Ground", 0, 12, 250);
        home.addLevel(level);
        Room room = addRoom();
        room.setLevel(level);
        room.setName("Porch");
        room.setFloorColor(0x808080);
        room.setCeilingColor(0xFFFFFF);
        room.setFloorShininess(0.4f);
        room.setFloorVisible(true);
        room.setCeilingVisible(false);
        room.setAreaVisible(true);
        String id = room.getId();

        Response resp = handler.execute(
                makeRequest(id, "points", points(0, 0, 300, 0, 300, 200, 100, 250, 0, 200)), accessor);

        assertTrue(resp.isOk(), () -> resp.getMessage());
        assertEquals(1, home.getRooms().size());
        assertSame(room, home.getRooms().get(0));
        float[][] pts = room.getPoints();
        assertEquals(5, pts.length);
        assertEquals(100f, pts[3][0], 0.001f);
        assertEquals(250f, pts[3][1], 0.001f);
        assertEquals(id, room.getId());
        assertSame(level, room.getLevel());
        assertEquals("Porch", room.getName());
        assertEquals(0x808080, (int) room.getFloorColor());
        assertEquals(0xFFFFFF, (int) room.getCeilingColor());
        assertEquals(0.4f, room.getFloorShininess(), 0.001f);
        assertTrue(room.isFloorVisible());
        assertFalse(room.isCeilingVisible());
        assertTrue(room.isAreaVisible());

        Map<String, Object> data = resp.getData();
        assertEquals(id, data.get("id"));
        assertEquals("Ground", data.get("level"));
        @SuppressWarnings("unchecked")
        List<Object> respPoints = (List<Object>) data.get("points");
        assertEquals(5, respPoints.size());
        assertEquals(67500.0, ((Number) data.get("area")).doubleValue(), 0.5);
    }

    @Test
    void testPointsAloneIsAModifiableProperty() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "points", points(0, 0, 10, 0, 0, 10)), accessor);

        assertTrue(resp.isOk());
        assertEquals(3, room.getPoints().length);
    }

    @Test
    void testPointsWithOtherProperties() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(),
                "points", points(0, 0, 10, 0, 0, 10), "name", "Stoop"), accessor);

        assertTrue(resp.isOk());
        assertEquals(3, room.getPoints().length);
        assertEquals("Stoop", room.getName());
    }

    @Test
    void testPointsFewerThanThreeRejected() {
        assertPointsRejected(points(0, 0, 10, 0), "at least 3");
    }

    @Test
    void testPointsNotArrayRejected() {
        assertPointsRejected("0,0 10,0 0,10", "array");
    }

    @Test
    void testPointNotObjectRejected() {
        List<Object> pts = points(0, 0, 10, 0);
        pts.add(Arrays.asList(0, 10));
        assertPointsRejected(pts, "index 2");
    }

    @Test
    void testPointNonNumericRejected() {
        List<Object> pts = points(0, 0, 10, 0);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("x", "a");
        bad.put("y", 10);
        pts.add(bad);
        assertPointsRejected(pts, "numeric");
    }

    @Test
    void testPointMissingCoordinateRejected() {
        List<Object> pts = points(0, 0, 10, 0);
        Map<String, Object> bad = new LinkedHashMap<>();
        bad.put("x", 5);
        pts.add(bad);
        assertPointsRejected(pts, "numeric");
    }

    @Test
    void testPointNaNRejected() {
        assertPointsRejected(points(0, 0, Double.NaN, 0, 0, 10), "finite");
    }

    @Test
    void testPointInfinityRejected() {
        assertPointsRejected(points(0, 0, 10, Double.POSITIVE_INFINITY, 0, 10), "finite");
    }

    @Test
    void testPointBeyondFloatRangeRejected() {
        assertPointsRejected(points(0, 0, 1e300, 0, 0, 10), "finite");
    }

    @Test
    void testInvalidPointsLeaveOtherPropertiesUntouched() {
        Room room = addRoom();
        room.setName("Before");

        Response resp = handler.execute(makeRequest(room.getId(),
                "points", points(0, 0, 10, 0), "name", "After"), accessor);

        assertTrue(resp.isError());
        assertEquals("Before", room.getName());
        assertEquals(4, room.getPoints().length);
    }

    @Test
    void testPointsChangeIsUndoneByCheckpointRestore() {
        Room room = addRoom();
        CheckpointManager checkpoints = new CheckpointManager();
        checkpoints.push(home.clone(), "before reshape");

        handler.execute(makeRequest(room.getId(), "points", points(0, 0, 10, 0, 0, 10)), accessor);
        assertEquals(3, room.getPoints().length);

        Home restored = checkpoints.restoreForce(0).getHome();
        Room restoredRoom = restored.getRooms().get(0);
        assertEquals(room.getId(), restoredRoom.getId());
        assertEquals(4, restoredRoom.getPoints().length);
    }

    // --- ID validation ---

    @Test
    void testIdNotFound() {
        addRoom();

        Response resp = handler.execute(makeRequest("nonexistent-id", "name", "Test"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testEmptyScene() {
        Response resp = handler.execute(makeRequest("any-id", "name", "Test"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testNoModifiableProperties() {
        Room room = addRoom();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", room.getId());
        Response resp = handler.execute(new Request("modify_room", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("No modifiable properties"));
    }

    // --- Partial updates ---

    @Test
    void testPartialUpdatePreservesOtherProperties() {
        Room room = addRoom();
        room.setFloorColor(0xFF0000);
        room.setName("Original");

        Response resp = handler.execute(makeRequest(room.getId(), "ceilingShininess", 0.7), accessor);

        assertTrue(resp.isOk());
        assertEquals(0xFF0000, (int) room.getFloorColor());
        assertEquals("Original", room.getName());
        assertEquals(0.7f, room.getCeilingShininess(), 0.01f);
    }

    @Test
    void testMultiplePropertiesAtOnce() {
        Room room = addRoom();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", room.getId());
        params.put("name", "Living Room");
        params.put("floorColor", "#CCBB99");
        params.put("ceilingColor", "#FFFFFF");
        params.put("floorShininess", 0.3);
        params.put("areaVisible", true);
        Response resp = handler.execute(new Request("modify_room", params), accessor);

        assertTrue(resp.isOk());
        assertEquals("Living Room", room.getName());
        assertEquals(0xCCBB99, (int) room.getFloorColor());
        assertEquals(0xFFFFFF, (int) room.getCeilingColor());
        assertEquals(0.3f, room.getFloorShininess(), 0.01f);
        assertTrue(room.isAreaVisible());
    }

    // --- Response format ---

    @Test
    void testResponseContainsAllFields() {
        Room room = addRoom();

        Response resp = handler.execute(makeRequest(room.getId(), "name", "Test Room"), accessor);

        assertTrue(resp.isOk());
        Map<String, Object> data = resp.getData();
        assertNotNull(data.get("id"));
        assertTrue(data.get("id") instanceof String, "id should be a String");
        assertEquals("Test Room", data.get("name"));
        assertNotNull(data.get("area"));
        assertTrue(data.containsKey("areaVisible"));
        assertTrue(data.containsKey("floorVisible"));
        assertTrue(data.containsKey("ceilingVisible"));
        assertTrue(data.containsKey("floorColor"));
        assertTrue(data.containsKey("ceilingColor"));
        assertNotNull(data.get("floorShininess"));
        assertNotNull(data.get("ceilingShininess"));
        assertNotNull(data.get("xCenter"));
        assertNotNull(data.get("yCenter"));
        assertNotNull(data.get("points"));

        @SuppressWarnings("unchecked")
        List<Object> points = (List<Object>) data.get("points");
        assertEquals(4, points.size());
    }

    // --- Descriptor ---

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

        assertTrue(props.containsKey("name"));
        assertTrue(props.containsKey("floorVisible"));
        assertTrue(props.containsKey("ceilingVisible"));
        assertTrue(props.containsKey("areaVisible"));
        assertTrue(props.containsKey("floorColor"));
        assertTrue(props.containsKey("ceilingColor"));
        assertTrue(props.containsKey("floorShininess"));
        assertTrue(props.containsKey("ceilingShininess"));
        assertTrue(props.containsKey("points"));

        @SuppressWarnings("unchecked")
        Map<String, Object> pointsProp = (Map<String, Object>) props.get("points");
        assertEquals("array", pointsProp.get("type"));
        assertEquals(3, pointsProp.get("minItems"));
        assertFalse(handler.getDescription().contains("cannot be modified"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("id"));
        assertEquals(1, required.size());
    }

    // --- Helpers ---

    private void assertPointsRejected(Object pointsValue, String messageFragment) {
        Room room = addRoom();
        float[][] before = room.getPoints();

        Response resp = handler.execute(makeRequest(room.getId(), "points", pointsValue), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains(messageFragment),
                () -> "expected '" + messageFragment + "' in: " + resp.getMessage());
        assertArrayEquals(before, room.getPoints());
    }

    private static List<Object> points(double... xy) {
        List<Object> list = new ArrayList<>();
        for (int i = 0; i < xy.length; i += 2) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", xy[i]);
            p.put("y", xy[i + 1]);
            list.add(p);
        }
        return list;
    }

    private Room addRoom() {
        float[][] polygon = {
                {0, 0}, {500, 0}, {500, 400}, {0, 400}
        };
        Room room = new Room(polygon);
        home.addRoom(room);
        return room;
    }

    private Request makeRequest(String id, Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Request("modify_room", params);
    }
}
