package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.CatalogTexture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.TexturesCatalog;
import com.eteks.sweethome3d.model.TexturesCategory;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApplyTextureHandlerTest {

    private ApplyTextureHandler handler;
    private HomeAccessor accessor;
    private Home home;

    @BeforeEach
    void setUp() {
        handler = new ApplyTextureHandler();
        home = new Home();

        TexturesCatalog catalog = new TexturesCatalog();

        TexturesCategory walls = new TexturesCategory("Walls");
        TexturesCategory floors = new TexturesCategory("Floors");

        CatalogTexture brick = new CatalogTexture("Red Brick", null, 20f, 10f);
        CatalogTexture plaster = new CatalogTexture("White Plaster", null, 50f, 50f);
        CatalogTexture parquet = new CatalogTexture(
                "parquet1", "Oak Parquet", null, 40f, 40f, "eTeks");
        // Duplicate name in different category
        CatalogTexture brickFloor = new CatalogTexture("Red Brick", null, 30f, 30f);

        catalog.add(walls, brick);
        catalog.add(walls, plaster);
        catalog.add(floors, parquet);
        catalog.add(floors, brickFloor);

        UserPreferences prefs = mock(UserPreferences.class);
        when(prefs.getTexturesCatalog()).thenReturn(catalog);

        accessor = new HomeAccessor(home, prefs);
    }

    // --- Wall: left side ---

    @Test
    void testApplyTextureToWallLeftSide() {
        Wall wall = addWall(0, 0, 500, 0);

        Response resp = handler.execute(makeWallRequest(wall.getId(), "left", "White Plaster"), accessor);

        assertTrue(resp.isOk());
        assertNotNull(wall.getLeftSideTexture());
        assertEquals("White Plaster", wall.getLeftSideTexture().getName());
        assertNull(wall.getRightSideTexture());

        assertEquals("wall", resp.getData().get("targetType"));
        assertEquals(wall.getId(), resp.getData().get("targetId"));
        assertEquals("left", resp.getData().get("surface"));
        assertEquals("White Plaster", resp.getData().get("textureName"));
        assertEquals("Walls", resp.getData().get("textureCategory"));
        assertEquals("White Plaster", resp.getData().get("leftSideTexture"));
        assertNull(resp.getData().get("rightSideTexture"));
    }

    // --- Wall: right side ---

    @Test
    void testApplyTextureToWallRightSide() {
        Wall wall = addWall(0, 0, 500, 0);

        Response resp = handler.execute(makeWallRequest(wall.getId(), "right", "White Plaster"), accessor);

        assertTrue(resp.isOk());
        assertNull(wall.getLeftSideTexture());
        assertNotNull(wall.getRightSideTexture());
        assertEquals("White Plaster", wall.getRightSideTexture().getName());
        assertEquals("White Plaster", resp.getData().get("rightSideTexture"));
    }

    // --- Wall: both sides ---

    @Test
    void testApplyTextureToWallBothSides() {
        Wall wall = addWall(0, 0, 500, 0);

        Response resp = handler.execute(makeWallRequest(wall.getId(), "both", "Red Brick"), accessor);

        assertTrue(resp.isOk());
        assertNotNull(wall.getLeftSideTexture());
        assertNotNull(wall.getRightSideTexture());
        assertEquals("Red Brick", wall.getLeftSideTexture().getName());
        assertEquals("Red Brick", wall.getRightSideTexture().getName());
        assertEquals("Red Brick", resp.getData().get("leftSideTexture"));
        assertEquals("Red Brick", resp.getData().get("rightSideTexture"));
    }

    // --- Room: floor ---

    @Test
    void testApplyTextureToRoomFloor() {
        Room room = addRoom();

        Response resp = handler.execute(makeRoomRequest(room.getId(), "floor", "Oak Parquet"), accessor);

        assertTrue(resp.isOk());
        assertNotNull(room.getFloorTexture());
        assertEquals("Oak Parquet", room.getFloorTexture().getName());
        assertNull(room.getCeilingTexture());

        assertEquals("room", resp.getData().get("targetType"));
        assertEquals(room.getId(), resp.getData().get("targetId"));
        assertEquals("floor", resp.getData().get("surface"));
        assertEquals("Oak Parquet", resp.getData().get("textureName"));
        assertEquals("Floors", resp.getData().get("textureCategory"));
        assertEquals("Oak Parquet", resp.getData().get("floorTexture"));
        assertNull(resp.getData().get("ceilingTexture"));
    }

    // --- Room: ceiling ---

    @Test
    void testApplyTextureToRoomCeiling() {
        Room room = addRoom();

        Response resp = handler.execute(makeRoomRequest(room.getId(), "ceiling", "White Plaster"), accessor);

        assertTrue(resp.isOk());
        assertNull(room.getFloorTexture());
        assertNotNull(room.getCeilingTexture());
        assertEquals("White Plaster", room.getCeilingTexture().getName());
    }

    // --- Room: both ---

    @Test
    void testApplyTextureToRoomBoth() {
        Room room = addRoom();

        Response resp = handler.execute(makeRoomRequest(room.getId(), "both", "Oak Parquet"), accessor);

        assertTrue(resp.isOk());
        assertNotNull(room.getFloorTexture());
        assertNotNull(room.getCeilingTexture());
        assertEquals("Oak Parquet", room.getFloorTexture().getName());
        assertEquals("Oak Parquet", room.getCeilingTexture().getName());
    }

    // --- Reset texture (null) ---

    @Test
    void testResetWallTexture() {
        Wall wall = addWall(0, 0, 500, 0);
        // First apply a texture
        handler.execute(makeWallRequest(wall.getId(), "both", "Red Brick"), accessor);
        assertNotNull(wall.getLeftSideTexture());

        // Then reset
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("surface", "both");
        params.put("textureName", null);
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isOk());
        assertNull(wall.getLeftSideTexture());
        assertNull(wall.getRightSideTexture());
        assertNull(resp.getData().get("textureName"));
    }

    @Test
    void testResetRoomFloorTexture() {
        Room room = addRoom();
        handler.execute(makeRoomRequest(room.getId(), "floor", "Oak Parquet"), accessor);
        assertNotNull(room.getFloorTexture());

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "room");
        params.put("targetId", room.getId());
        params.put("surface", "floor");
        params.put("textureName", null);
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isOk());
        assertNull(room.getFloorTexture());
    }

    // --- Category disambiguation ---

    @Test
    void testCategoryDisambiguation() {
        Wall wall = addWall(0, 0, 500, 0);

        // "Red Brick" exists in both Walls and Floors categories.
        Response resp1 = handler.execute(makeWallRequest(wall.getId(), "left", "Red Brick"), accessor);
        assertTrue(resp1.isOk());
        assertEquals("Floors", resp1.getData().get("textureCategory"));

        // With category filter "Walls"
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("surface", "right");
        params.put("textureName", "Red Brick");
        params.put("textureCategory", "Walls");
        Response resp2 = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp2.isOk());
        assertEquals("Walls", resp2.getData().get("textureCategory"));
    }

    // --- Angle and scale ---

    @Test
    void testApplyTextureWithAngle() {
        Wall wall = addWall(0, 0, 500, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("surface", "left");
        params.put("textureName", "Red Brick");
        params.put("angle", 45.0);
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isOk());
        HomeTexture texture = wall.getLeftSideTexture();
        assertNotNull(texture);
        assertEquals(Math.toRadians(45), texture.getAngle(), 0.01);
    }

    @Test
    void testApplyTextureWithScale() {
        Room room = addRoom();

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "room");
        params.put("targetId", room.getId());
        params.put("surface", "floor");
        params.put("textureName", "Oak Parquet");
        params.put("scale", 2.0);
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isOk());
        HomeTexture texture = room.getFloorTexture();
        assertNotNull(texture);
        assertEquals(2.0f, texture.getScale(), 0.01f);
    }

    @Test
    void testApplyTextureWithAngleAndScale() {
        Wall wall = addWall(0, 0, 500, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("surface", "left");
        params.put("textureName", "Red Brick");
        params.put("angle", 90.0);
        params.put("scale", 1.5);
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isOk());
        HomeTexture texture = wall.getLeftSideTexture();
        assertNotNull(texture);
        assertEquals(Math.toRadians(90), texture.getAngle(), 0.01);
        assertEquals(1.5f, texture.getScale(), 0.01f);
    }

    // --- Error cases ---

    @Test
    void testTextureNotFound() {
        Wall wall = addWall(0, 0, 500, 0);

        Response resp = handler.execute(makeWallRequest(wall.getId(), "left", "NonExistent"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("Texture not found"));
        assertTrue(resp.getMessage().contains("NonExistent"));
        assertTrue(resp.getMessage().contains("list_textures_catalog"));
    }

    @Test
    void testTextureNotFoundWithCategory() {
        Wall wall = addWall(0, 0, 500, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("surface", "left");
        params.put("textureName", "NonExistent");
        params.put("textureCategory", "SomeCategory");
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("SomeCategory"));
    }

    @Test
    void testMissingTargetType() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetId", "some-id");
        params.put("surface", "left");
        params.put("textureName", "Red Brick");
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("targetType"));
    }

    @Test
    void testInvalidTargetType() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "furniture");
        params.put("targetId", "some-id");
        params.put("surface", "left");
        params.put("textureName", "Red Brick");
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("furniture"));
        assertTrue(resp.getMessage().contains("wall"));
    }

    @Test
    void testMissingTargetId() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("surface", "left");
        params.put("textureName", "Red Brick");
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("targetId"));
    }

    @Test
    void testMissingSurface() {
        Wall wall = addWall(0, 0, 500, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("textureName", "Red Brick");
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("surface"));
    }

    @Test
    void testInvalidSurfaceForWall() {
        Wall wall = addWall(0, 0, 500, 0);

        Response resp = handler.execute(makeWallRequest(wall.getId(), "floor", "Red Brick"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("floor"));
        assertTrue(resp.getMessage().contains("wall"));
    }

    @Test
    void testInvalidSurfaceForRoom() {
        Room room = addRoom();

        Response resp = handler.execute(makeRoomRequest(room.getId(), "left", "Oak Parquet"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("left"));
        assertTrue(resp.getMessage().contains("room"));
    }

    @Test
    void testMissingTextureName() {
        Wall wall = addWall(0, 0, 500, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("surface", "left");
        // textureName intentionally omitted
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("textureName"));
    }

    @Test
    void testWallIdNotFound() {
        // No walls added
        Response resp = handler.execute(makeWallRequest("nonexistent-id", "left", "Red Brick"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testRoomIdNotFound() {
        // No rooms added
        Response resp = handler.execute(makeRoomRequest("nonexistent-id", "floor", "Oak Parquet"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testWallIdNotFoundWithExistingWalls() {
        addWall(0, 0, 500, 0);

        Response resp = handler.execute(makeWallRequest("nonexistent-id", "left", "Red Brick"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testInvalidScale() {
        Wall wall = addWall(0, 0, 500, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("surface", "left");
        params.put("textureName", "Red Brick");
        params.put("scale", -1.0);
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("scale"));
        assertTrue(resp.getMessage().contains("positive"));
    }

    @Test
    void testZeroScale() {
        Wall wall = addWall(0, 0, 500, 0);

        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", wall.getId());
        params.put("surface", "left");
        params.put("textureName", "Red Brick");
        params.put("scale", 0.0);
        Response resp = handler.execute(new Request("apply_texture", params), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("scale"));
    }

    // --- Descriptor ---

    @Test
    void testDescriptorDescription() {
        assertNotNull(handler.getDescription());
        assertTrue(handler.getDescription().contains("texture"));
    }

    @Test
    void testDescriptorSchema() {
        Map<String, Object> schema = handler.getSchema();
        assertNotNull(schema);
        assertEquals("object", schema.get("type"));

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("targetType"));
        assertTrue(properties.containsKey("targetId"));
        assertTrue(properties.containsKey("surface"));
        assertTrue(properties.containsKey("textureName"));
        assertTrue(properties.containsKey("textureCategory"));
        assertTrue(properties.containsKey("angle"));
        assertTrue(properties.containsKey("scale"));

        @SuppressWarnings("unchecked")
        Map<String, Object> targetIdProp = (Map<String, Object>) properties.get("targetId");
        assertEquals("string", targetIdProp.get("type"));

        @SuppressWarnings("unchecked")
        List<String> required = (List<String>) schema.get("required");
        assertTrue(required.contains("targetType"));
        assertTrue(required.contains("targetId"));
        assertTrue(required.contains("surface"));
        assertFalse(required.contains("textureName"), "textureName is one of three texture sources");
        assertEquals(3, required.size());

        for (String key : new String[] {"fromTargetType", "fromTargetId", "fromSurface", "keepTexture",
                "xOffset", "yOffset"}) {
            assertTrue(properties.containsKey(key), key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> yOffset = (Map<String, Object>) properties.get("yOffset");
        assertEquals("number", yOffset.get("type"));
        assertTrue(yOffset.get("description").toString().contains("cm"));
        assertTrue(yOffset.get("description").toString().contains("up"));
    }

    // --- Offsets (cm in, fraction of a tile stored) ---

    @Test
    void testCatalogTextureWithYOffsetOnWallLeftSide() {
        Wall wall = addWall(0, 0, 500, 0);

        // White Plaster is a 50 x 50 cm tile: 20 cm = 0.4 of a tile
        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "left",
                "textureName", "White Plaster", "yOffset", 20.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeTexture t = wall.getLeftSideTexture();
        assertEquals(0f, t.getXOffset(), 1e-6f);
        assertEquals(0.4f, t.getYOffset(), 1e-6f);
        assertEquals(0f, t.getAngle(), 1e-6f);
        assertEquals(1f, t.getScale(), 1e-6f);
        assertTrue(t.isLeftToRightOriented());
        assertNull(wall.getRightSideTexture());
        assertEquals("catalog", resp.getData().get("source"));
        assertEquals(20.0, (Double) info(resp, "leftSideTextureInfo").get("yOffset"), 1e-9);
        assertEquals(0.0, (Double) info(resp, "leftSideTextureInfo").get("xOffset"), 1e-9);
    }

    @Test
    void testOffsetsAreConvertedAtTheFinalScale() {
        Wall wall = addWall(0, 0, 500, 0);

        // Red Brick (Floors) is 30 x 30; at scale 2 a tile is 60 cm: x 15 cm = 0.25, y -30 cm = -0.5
        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "right",
                "textureName", "Red Brick", "scale", 2.0, "xOffset", 15.0, "yOffset", -30.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeTexture t = wall.getRightSideTexture();
        assertEquals(0.25f, t.getXOffset(), 1e-6f);
        assertEquals(-0.5f, t.getYOffset(), 1e-6f);
        assertEquals(15.0, (Double) info(resp, "rightSideTextureInfo").get("xOffset"), 1e-9);
        assertEquals(-30.0, (Double) info(resp, "rightSideTextureInfo").get("yOffset"), 1e-9);
    }

    @Test
    void testOffsetsOnRoomFloorAndCeiling() {
        Room room = addRoom();

        Response resp = handler.execute(req("targetType", "room", "targetId", room.getId(), "surface", "both",
                "textureName", "Oak Parquet", "xOffset", 10.0, "yOffset", 20.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        // Oak Parquet is 40 x 40
        for (HomeTexture t : new HomeTexture[] {room.getFloorTexture(), room.getCeilingTexture()}) {
            assertEquals(0.25f, t.getXOffset(), 1e-6f);
            assertEquals(0.5f, t.getYOffset(), 1e-6f);
        }
        assertEquals(10.0, (Double) info(resp, "floorTextureInfo").get("xOffset"), 1e-9);
        assertEquals(20.0, (Double) info(resp, "ceilingTextureInfo").get("yOffset"), 1e-9);
    }

    @Test
    void testNonFiniteOffsetIsAnError() {
        Wall wall = addWall(0, 0, 500, 0);

        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "left",
                "textureName", "White Plaster", "yOffset", Double.POSITIVE_INFINITY), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("yOffset"));
        assertNull(wall.getLeftSideTexture());
    }

    // --- keepTexture: change only the offsets (or angle/scale) of the texture already there ---

    @Test
    void testKeepTextureShiftsOnlyTheOffsetOnAWallSide() {
        Wall wall = addWall(0, 0, 500, 0);
        HomeTexture before = photoTexture(0.1f, 0.2f, (float) Math.toRadians(30), 1.5f, false);
        wall.setLeftSideTexture(before);
        HomeTexture right = photoTexture(0f, 0f, 0f, 1f, false);
        wall.setRightSideTexture(right);

        // Stone tile 60 x 40 cm at scale 1.5 is 90 x 60 cm: +20 cm up = 1/3 of a tile
        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "left",
                "keepTexture", true, "yOffset", 20.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeTexture after = wall.getLeftSideTexture();
        assertSame(before.getImage(), after.getImage());
        assertEquals(before.getName(), after.getName());
        assertEquals(before.getWidth(), after.getWidth(), 0f);
        assertEquals(before.getHeight(), after.getHeight(), 0f);
        assertEquals(before.getAngle(), after.getAngle(), 0f);
        assertEquals(before.getScale(), after.getScale(), 0f);
        assertEquals(before.getXOffset(), after.getXOffset(), 0f);
        assertEquals(before.isLeftToRightOriented(), after.isLeftToRightOriented());
        assertEquals(20f / 60f, after.getYOffset(), 1e-6f);
        assertSame(right, wall.getRightSideTexture(), "the other side is untouched");

        assertEquals("keep", resp.getData().get("source"));
        assertEquals("Stone veneer", resp.getData().get("textureName"));
        assertNull(resp.getData().get("textureCategory"));
        assertEquals(20.0, (Double) info(resp, "leftSideTextureInfo").get("yOffset"), 1e-4);
        assertEquals(9.0, (Double) info(resp, "leftSideTextureInfo").get("xOffset"), 1e-4);
        assertEquals(30.0, (Double) info(resp, "leftSideTextureInfo").get("angle"), 1e-4);
    }

    @Test
    void testKeepTextureOnBothSidesKeepsEachSidesOwnTexture() {
        Wall wall = addWall(0, 0, 500, 0);
        handler.execute(makeWallRequest(wall.getId(), "left", "White Plaster"), accessor);
        handler.execute(makeWallRequest(wall.getId(), "right", "Red Brick"), accessor);

        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "both",
                "keepTexture", true, "xOffset", 5.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals("White Plaster", wall.getLeftSideTexture().getName());
        assertEquals("Red Brick", wall.getRightSideTexture().getName());
        assertEquals(5f / 50f, wall.getLeftSideTexture().getXOffset(), 1e-6f);
        assertEquals(5f / 30f, wall.getRightSideTexture().getXOffset(), 1e-6f);
        assertNull(resp.getData().get("textureName"), "the two sides carry different textures");
    }

    @Test
    void testKeepTextureOnRoomFloorKeepsFittingArea() {
        Room room = addRoom();
        HomeTexture before = photoTexture(0f, 0f, 0f, 1f, true);
        room.setFloorTexture(before);

        Response resp = handler.execute(req("targetType", "room", "targetId", room.getId(), "surface", "floor",
                "keepTexture", true, "xOffset", 12.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeTexture after = room.getFloorTexture();
        assertSame(before.getImage(), after.getImage());
        assertTrue(after.isFittingArea());
        assertEquals(12f / 60f, after.getXOffset(), 1e-6f);
        assertEquals(0f, after.getYOffset(), 0f);
        assertNull(room.getCeilingTexture());
    }

    @Test
    void testKeepTextureOnRoomCeilingWithScaleKeepsTheStoredOffsetFraction() {
        Room room = addRoom();
        room.setCeilingTexture(photoTexture(0.5f, 0.25f, 0f, 1f, false));

        Response resp = handler.execute(req("targetType", "room", "targetId", room.getId(), "surface", "ceiling",
                "keepTexture", true, "scale", 2.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeTexture after = room.getCeilingTexture();
        assertEquals(2f, after.getScale(), 0f);
        assertEquals(0.5f, after.getXOffset(), 0f);
        assertEquals(0.25f, after.getYOffset(), 0f);
        // the offset in cm grows with the tile: 0.5 x 60 x 2
        assertEquals(60.0, (Double) info(resp, "ceilingTextureInfo").get("xOffset"), 1e-4);
    }

    @Test
    void testKeepTextureWithoutATextureIsAnErrorAndChangesNothing() {
        Wall wall = addWall(0, 0, 500, 0);
        HomeTexture left = photoTexture(0f, 0f, 0f, 1f, false);
        wall.setLeftSideTexture(left);

        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "both",
                "keepTexture", true, "yOffset", 20.0), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("no texture"), resp.getMessage());
        assertTrue(resp.getMessage().contains("right"), resp.getMessage());
        assertSame(left, wall.getLeftSideTexture(), "all or nothing: the left side was not shifted");
    }

    @Test
    void testKeepTextureNeedsSomethingToChange() {
        Wall wall = addWall(0, 0, 500, 0);
        wall.setLeftSideTexture(photoTexture(0f, 0f, 0f, 1f, false));

        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "left",
                "keepTexture", true), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("xOffset"));
    }

    @Test
    void testKeepTextureOnUnknownWallIsAnError() {
        Response resp = handler.execute(req("targetType", "wall", "targetId", "nonexistent-id", "surface", "left",
                "keepTexture", true, "yOffset", 20.0), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("Wall not found"));
    }

    // --- Copy a texture already in the home ---

    @Test
    void testCopyFromWallSideToWallSide() {
        Wall source = addWall(0, 0, 500, 0);
        HomeTexture photo = photoTexture(0.1f, 0.2f, (float) Math.toRadians(15), 1.25f, false);
        source.setRightSideTexture(photo);
        Wall target = addWall(500, 0, 500, 400);

        Response resp = handler.execute(req("targetType", "wall", "targetId", target.getId(), "surface", "left",
                "fromTargetType", "wall", "fromTargetId", source.getId(), "fromSurface", "right"), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeTexture copy = target.getLeftSideTexture();
        assertSame(photo.getImage(), copy.getImage());
        assertEquals("Stone veneer", copy.getName());
        assertEquals(photo.getWidth(), copy.getWidth(), 0f);
        assertEquals(photo.getHeight(), copy.getHeight(), 0f);
        assertEquals(photo.getAngle(), copy.getAngle(), 0f);
        assertEquals(photo.getScale(), copy.getScale(), 0f);
        assertEquals(photo.getXOffset(), copy.getXOffset(), 0f);
        assertEquals(photo.getYOffset(), copy.getYOffset(), 0f);
        assertNull(target.getRightSideTexture());
        assertSame(photo, source.getRightSideTexture(), "the source is untouched");

        assertEquals("copy", resp.getData().get("source"));
        assertEquals("Stone veneer", resp.getData().get("textureName"));
        @SuppressWarnings("unchecked")
        Map<String, Object> from = (Map<String, Object>) resp.getData().get("copiedFrom");
        assertEquals("wall", from.get("targetType"));
        assertEquals(source.getId(), from.get("targetId"));
        assertEquals("right", from.get("surface"));
    }

    @Test
    void testCopyFromWallToRoomFloorWithOffsetOverride() {
        Wall source = addWall(0, 0, 500, 0);
        source.setLeftSideTexture(photoTexture(0.1f, 0.2f, 0f, 1f, false));
        Room room = addRoom();

        Response resp = handler.execute(req("targetType", "room", "targetId", room.getId(), "surface", "floor",
                "fromTargetType", "wall", "fromTargetId", source.getId(), "fromSurface", "left",
                "yOffset", 10.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeTexture copy = room.getFloorTexture();
        assertEquals("Stone veneer", copy.getName());
        assertEquals(0.1f, copy.getXOffset(), 0f);
        assertEquals(10f / 40f, copy.getYOffset(), 1e-6f);
        assertNull(room.getCeilingTexture());
    }

    @Test
    void testCopyFromRoomFloorToRoomCeilingAndWallBothSides() {
        Room source = addRoom();
        source.setFloorTexture(photoTexture(0f, 0f, 0f, 0.5f, true));
        Room target = addRoom();
        Wall wall = addWall(0, 0, 500, 0);

        Response toCeiling = handler.execute(req("targetType", "room", "targetId", target.getId(),
                "surface", "ceiling", "fromTargetType", "room", "fromTargetId", source.getId(),
                "fromSurface", "floor"), accessor);
        Response toWall = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "both",
                "fromTargetType", "room", "fromTargetId", source.getId(), "fromSurface", "floor",
                "angle", 90.0), accessor);

        assertTrue(toCeiling.isOk(), toCeiling.getMessage());
        assertTrue(target.getCeilingTexture().isFittingArea());
        assertEquals(0.5f, target.getCeilingTexture().getScale(), 0f);
        assertTrue(toWall.isOk(), toWall.getMessage());
        assertEquals("Stone veneer", wall.getLeftSideTexture().getName());
        assertEquals("Stone veneer", wall.getRightSideTexture().getName());
        assertEquals(Math.toRadians(90), wall.getLeftSideTexture().getAngle(), 1e-6);
        assertEquals("Stone veneer", toWall.getData().get("textureName"));
    }

    @Test
    void testCopyFromUnknownSourceIsAnError() {
        Wall wall = addWall(0, 0, 500, 0);

        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "left",
                "fromTargetType", "room", "fromTargetId", "nonexistent-id", "fromSurface", "floor"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("Source room not found"), resp.getMessage());
        assertNull(wall.getLeftSideTexture());
    }

    @Test
    void testCopyToUnknownTargetIsAnError() {
        Wall source = addWall(0, 0, 500, 0);
        source.setLeftSideTexture(photoTexture(0f, 0f, 0f, 1f, false));

        Response resp = handler.execute(req("targetType", "room", "targetId", "nonexistent-id", "surface", "floor",
                "fromTargetType", "wall", "fromTargetId", source.getId(), "fromSurface", "left"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("Room not found"), resp.getMessage());
    }

    @Test
    void testCopyFromASurfaceWithoutTextureIsAnError() {
        Wall source = addWall(0, 0, 500, 0);
        Wall target = addWall(500, 0, 500, 400);

        Response resp = handler.execute(req("targetType", "wall", "targetId", target.getId(), "surface", "left",
                "fromTargetType", "wall", "fromTargetId", source.getId(), "fromSurface", "left"), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("no texture"), resp.getMessage());
    }

    @Test
    void testCopyNeedsAllThreeFromParameters() {
        Wall wall = addWall(0, 0, 500, 0);

        Response resp = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "left",
                "fromTargetType", "wall", "fromTargetId", wall.getId()), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("fromSurface"));
    }

    @Test
    void testCopyRejectsBadFromTypeAndSurface() {
        Wall wall = addWall(0, 0, 500, 0);

        Response badType = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "left",
                "fromTargetType", "furniture", "fromTargetId", "x", "fromSurface", "left"), accessor);
        Response both = handler.execute(req("targetType", "wall", "targetId", wall.getId(), "surface", "left",
                "fromTargetType", "wall", "fromTargetId", wall.getId(), "fromSurface", "both"), accessor);
        Response wrongSurface = handler.execute(req("targetType", "wall", "targetId", wall.getId(),
                "surface", "left", "fromTargetType", "room", "fromTargetId", "x", "fromSurface", "left"), accessor);

        assertTrue(badType.isError());
        assertTrue(badType.getMessage().contains("fromTargetType"));
        assertTrue(both.isError());
        assertTrue(both.getMessage().contains("fromSurface"));
        assertTrue(wrongSurface.isError());
        assertTrue(wrongSurface.getMessage().contains("floor, ceiling"));
    }

    @Test
    void testOnlyOneTextureSource() {
        Wall wall = addWall(0, 0, 500, 0);

        Response nameAndKeep = handler.execute(req("targetType", "wall", "targetId", wall.getId(),
                "surface", "left", "textureName", "Red Brick", "keepTexture", true, "yOffset", 1.0), accessor);
        Response nameAndCopy = handler.execute(req("targetType", "wall", "targetId", wall.getId(),
                "surface", "left", "textureName", "Red Brick", "fromTargetType", "wall",
                "fromTargetId", wall.getId(), "fromSurface", "right"), accessor);

        assertTrue(nameAndKeep.isError());
        assertTrue(nameAndKeep.getMessage().contains("only one"));
        assertTrue(nameAndCopy.isError());
        assertNull(wall.getLeftSideTexture());
    }

    // --- Helpers for the copy/keep tests ---

    /** A 60 x 40 cm texture that is not in the catalog, like one imported from a photo. */
    private static HomeTexture photoTexture(float xOffset, float yOffset, float angle, float scale,
                                            boolean fittingArea) {
        CatalogTexture imported = new CatalogTexture("Stone veneer", null, 60f, 40f);
        return new HomeTexture(imported, xOffset, yOffset, angle, scale, fittingArea, true);
    }

    private static Request req(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Request("apply_texture", params);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> info(Response resp, String key) {
        Map<String, Object> info = (Map<String, Object>) resp.getData().get(key);
        assertNotNull(info, key);
        return info;
    }

    // --- Helpers ---

    private Wall addWall(float xStart, float yStart, float xEnd, float yEnd) {
        Wall wall = new Wall(xStart, yStart, xEnd, yEnd, 10f, 250f);
        home.addWall(wall);
        return wall;
    }

    private Room addRoom() {
        float[][] polygon = {
                {0, 0}, {500, 0}, {500, 400}, {0, 400}
        };
        Room room = new Room(polygon);
        home.addRoom(room);
        return room;
    }

    private Request makeWallRequest(String id, String surface, String textureName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "wall");
        params.put("targetId", id);
        params.put("surface", surface);
        params.put("textureName", textureName);
        return new Request("apply_texture", params);
    }

    private Request makeRoomRequest(String id, String surface, String textureName) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("targetType", "room");
        params.put("targetId", id);
        params.put("surface", surface);
        params.put("textureName", textureName);
        return new Request("apply_texture", params);
    }
}
