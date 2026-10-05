package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Level;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class ModifyLevelHandlerTest {

    private ModifyLevelHandler handler;
    private HomeAccessor accessor;
    private Home home;

    @BeforeEach
    void setUp() {
        handler = new ModifyLevelHandler();
        home = new Home();
        accessor = createAccessor(home);
    }

    @Test
    void testChangesOnlyFloorThickness() {
        Level up = addLevel("Upstairs", 404.02f, 30.48f, 246.7f);

        Response resp = handler.execute(request(up.getId(), "floorThickness", 25.24), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(25.24f, up.getFloorThickness(), 0.001f);
        assertEquals(404.02f, up.getElevation(), 0.001f);
        assertEquals(246.7f, up.getHeight(), 0.001f);
        assertEquals("Upstairs", up.getName());
        assertEquals(25.24, ((Number) resp.getData().get("floorThickness")).doubleValue(), 0.01);
    }

    @Test
    void testChangesNameElevationAndHeight() {
        Level level = addLevel("Ground", 0, 12, 250);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", level.getId());
        params.put("name", "Main floor");
        params.put("elevation", 101.6);
        params.put("height", 277.18);
        Response resp = handler.execute(new Request("modify_level", params), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals("Main floor", level.getName());
        assertEquals(101.6f, level.getElevation(), 0.001f);
        assertEquals(277.18f, level.getHeight(), 0.001f);
        assertEquals(12f, level.getFloorThickness(), 0.001f);
    }

    @Test
    void testRejectsNonPositiveHeight() {
        Level level = addLevel("Ground", 0, 12, 250);
        Response resp = handler.execute(request(level.getId(), "height", 0), accessor);
        assertTrue(resp.isError());
        assertEquals(250f, level.getHeight(), 0.001f);
    }

    @Test
    void testRejectsNegativeFloorThickness() {
        Level level = addLevel("Ground", 0, 12, 250);
        Response resp = handler.execute(request(level.getId(), "floorThickness", -1), accessor);
        assertTrue(resp.isError());
        assertEquals(12f, level.getFloorThickness(), 0.001f);
    }

    @Test
    void testRejectsEmptyName() {
        Level level = addLevel("Ground", 0, 12, 250);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", level.getId());
        params.put("name", "  ");
        Response resp = handler.execute(new Request("modify_level", params), accessor);
        assertTrue(resp.isError());
        assertEquals("Ground", level.getName());
    }

    @Test
    void testIdNotFound() {
        addLevel("Ground", 0, 12, 250);
        Response resp = handler.execute(request("nonexistent-id", "height", 260), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testMissingId() {
        Response resp = handler.execute(new Request("modify_level", new LinkedHashMap<>()), accessor);
        assertTrue(resp.isError());
    }

    @Test
    void testDescriptorFields() {
        assertTrue(handler.getDescription().toLowerCase().contains("level"));
        Map<String, Object> schema = handler.getSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        for (String k : new String[] {"id", "name", "elevation", "height", "floorThickness"}) {
            assertTrue(props.containsKey(k), k);
        }
        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertEquals(List.of("id"), required);
    }

    private Level addLevel(String name, float elevation, float floorThickness, float height) {
        Level level = new Level(name, elevation, floorThickness, height);
        home.addLevel(level);
        return level;
    }

    private Request request(String id, String key, Number value) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        params.put(key, value);
        return new Request("modify_level", params);
    }
}
