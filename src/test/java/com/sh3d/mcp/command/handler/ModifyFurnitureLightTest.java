package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.CatalogLight;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.FurnitureCatalog;
import com.eteks.sweethome3d.model.FurnitureCategory;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.LightSource;
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

import static com.sh3d.mcp.command.handler.TestFixtures.addLevel;
import static com.sh3d.mcp.command.handler.TestFixtures.createAccessorWithCatalog;
import static org.junit.jupiter.api.Assertions.*;

/**
 * modify_furniture's light parameters (lightColor, lightSources, lightPower, asLight) and how get_state and
 * query_state report a light. The test catalog has one catalog light, "test#bulb", and one plain piece.
 */
class ModifyFurnitureLightTest {

    private static final float[][] IDENTITY = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

    private ModifyFurnitureHandler handler;
    private Home home;
    private HomeAccessor accessor;
    private CatalogLight bulb;
    private CatalogPieceOfFurniture table;

    @BeforeEach
    void setUp() {
        handler = new ModifyFurnitureHandler();
        home = new Home();

        bulb = new CatalogLight("test#bulb", "Test light source", null, null, null,
                10f, 10f, 10f, 0f, true,
                new LightSource[]{new LightSource(0.5f, 0.25f, 0.75f, 0xD0D0D0, 0.4f)},
                IDENTITY, null, true, null, null);
        table = new CatalogPieceOfFurniture("test#table", "Test table", null, null, null,
                100f, 50f, 75f, 0f, true, IDENTITY, null, true, null, null);

        FurnitureCatalog catalog = new FurnitureCatalog();
        FurnitureCategory lights = new FurnitureCategory("Lights");
        catalog.add(lights, bulb);
        catalog.add(new FurnitureCategory("Tables"), table);
        accessor = createAccessorWithCatalog(home, catalog);
    }

    // ======================== Light parameters on a light ========================

    @Test
    void testLightColorKeepsPositionAndDiameter() {
        HomeLight light = addLight(10, 20);

        Request request = request(light.getId(), "lightColor", "#FFA757");
        Response resp = handler.execute(request, accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        LightSource source = light.getLightSources()[0];
        assertEquals(0xFFA757, source.getColor());
        assertEquals(0.5f, source.getX(), 1e-6);
        assertEquals(0.25f, source.getY(), 1e-6);
        assertEquals(0.75f, source.getZ(), 1e-6);
        assertEquals(0.4f, source.getDiameter(), 1e-6);
        assertEquals(0.5f, light.getPower(), 1e-6, "power is untouched");
        assertEquals("#FFA757", sources(resp).get(0).get("color"));
        assertEquals(50.0, ((Number) resp.getData().get("lightPower")).doubleValue(), 1e-6);
        assertNull(resp.getData().get("converted"));
        assertTrue(request.getUnrecognizedKeys().isEmpty(), "every light key is read");
    }

    @Test
    void testLightColorRecoloursEverySource() {
        HomeLight light = addLight(0, 0);
        light.setLightSources(new LightSource[]{
                new LightSource(0.1f, 0.2f, 0.3f, 0xFF0000), new LightSource(0.9f, 0.8f, 0.7f, 0x00FF00, 0.1f)});

        Response resp = handler.execute(request(light.getId(), "lightColor", "#102030"), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        LightSource[] after = light.getLightSources();
        assertEquals(2, after.length);
        assertEquals(0x102030, after[0].getColor());
        assertEquals(0x102030, after[1].getColor());
        assertNull(after[0].getDiameter());
        assertEquals(0.1f, after[1].getDiameter(), 1e-6);
        assertEquals(0.9f, after[1].getX(), 1e-6);
    }

    @Test
    void testLightSourcesReplaceThePiecesOwn() {
        HomeLight light = addLight(0, 0);

        Response resp = handler.execute(request(light.getId(), "lightSources", Arrays.asList(
                source(0.5, 0.5, 0.5, "#FF0000", 0.2),
                source(0.1, 0.9, 1.0, "#00FF00", null))), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        LightSource[] after = light.getLightSources();
        assertEquals(2, after.length);
        assertEquals(0xFF0000, after[0].getColor());
        assertEquals(0.2f, after[0].getDiameter(), 1e-6);
        assertEquals(0x00FF00, after[1].getColor());
        assertNull(after[1].getDiameter());
        assertEquals(2, sources(resp).size());
        assertNull(sources(resp).get(1).get("diameter"));
        assertEquals(1.0, ((Number) sources(resp).get(1).get("z")).doubleValue(), 1e-6);
    }

    @Test
    void testLightPowerIsAPercent() {
        HomeLight light = addLight(0, 0);

        Response resp = handler.execute(request(light.getId(), "lightPower", 25.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(0.25f, light.getPower(), 1e-6);
        assertEquals(25.0, ((Number) resp.getData().get("lightPower")).doubleValue(), 1e-6);

        resp = handler.execute(request(light.getId(), "lightPower", 0), accessor);
        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(0f, light.getPower(), 1e-6);

        resp = handler.execute(request(light.getId(), "lightPower", 100), accessor);
        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(1f, light.getPower(), 1e-6);
    }

    @Test
    void testLightParametersCombineWithOtherParameters() {
        HomeLight light = addLight(0, 0);

        Response resp = handler.execute(request(light.getId(), "lightColor", "#FFFFFF", "lightPower", 80,
                "x", 300.0, "elevation", 240.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(0xFFFFFF, light.getLightSources()[0].getColor());
        assertEquals(0.8f, light.getPower(), 1e-6);
        assertEquals(300f, light.getX(), 1e-6);
        assertEquals(240f, light.getElevation(), 1e-6);
    }

    @Test
    void testLightColorOnALightWithNoSourcesIsRefused() {
        HomeLight light = addLight(0, 0);
        light.setLightSources(new LightSource[0]);

        Response resp = handler.execute(request(light.getId(), "lightColor", "#FFFFFF", "x", 99.0), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("no light sources"), resp.getMessage());
        assertEquals(0f, light.getX(), 1e-6, "nothing else changed");
    }

    // ======================== Validation changes nothing ========================

    @Test
    void testInvalidLightParametersChangeNothing() {
        HomeLight light = addLight(5, 6);
        LightSource before = light.getLightSources()[0];

        List<Object[]> bad = Arrays.asList(
                new Object[]{"lightColor", "red", "'lightColor' must be a color"},
                new Object[]{"lightColor", 16711680, "'lightColor' must be a color"},
                new Object[]{"lightColor", null, "'lightColor' must be a color"},
                new Object[]{"lightSources", Collections.emptyList(), "non-empty array"},
                new Object[]{"lightSources", "x", "non-empty array"},
                new Object[]{"lightSources", Collections.singletonList("a"), "must be an object"},
                new Object[]{"lightSources", Collections.singletonList(source(1.5, 0, 0, "#FFFFFF", null)),
                        "'x' must be a number from 0 to 1"},
                new Object[]{"lightSources", Collections.singletonList(source(0, -0.1, 0, "#FFFFFF", null)),
                        "'y' must be a number from 0 to 1"},
                new Object[]{"lightSources", Collections.singletonList(source(0, 0, Double.NaN, "#FFFFFF", null)),
                        "'z' must be a number from 0 to 1"},
                new Object[]{"lightSources", Collections.singletonList(source(0, 0, 0, "#FFF", null)),
                        "'color'"},
                new Object[]{"lightSources", Collections.singletonList(source(0, 0, 0, "#FFFFFF", 0.0)),
                        "'diameter'"},
                new Object[]{"lightSources", Collections.singletonList(source(0, 0, 0, "#FFFFFF", 2.0)),
                        "'diameter'"},
                new Object[]{"lightPower", 101, "between 0 and 100"},
                new Object[]{"lightPower", -1, "between 0 and 100"},
                new Object[]{"lightPower", "50", "must be a number"},
                new Object[]{"asLight", "yes", "must be true or false"});
        for (Object[] b : bad) {
            Response resp = handler.execute(request(light.getId(), (String) b[0], b[1], "x", 700.0), accessor);
            assertTrue(resp.isError(), b[0] + "=" + b[1]);
            assertTrue(resp.getMessage().contains((String) b[2]), b[0] + "=" + b[1] + " -> " + resp.getMessage());
            assertEquals(5f, light.getX(), 1e-6, "x unchanged after " + b[0] + "=" + b[1]);
            assertSame(before, light.getLightSources()[0]);
            assertEquals(0.5f, light.getPower(), 1e-6);
        }
    }

    @Test
    void testLightColorAndLightSourcesTogetherAreRefused() {
        HomeLight light = addLight(0, 0);

        Response resp = handler.execute(request(light.getId(), "lightColor", "#FFFFFF",
                "lightSources", Collections.singletonList(source(0, 0, 0, "#FFFFFF", null))), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not both"), resp.getMessage());
    }

    @Test
    void testUnknownSourceFieldIsRefused() {
        HomeLight light = addLight(0, 0);
        Map<String, Object> s = source(0, 0, 0, "#FFFFFF", null);
        s.put("colour", "#FFFFFF");

        Response resp = handler.execute(request(light.getId(), "lightSources", Collections.singletonList(s)), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("unknown field 'colour'"), resp.getMessage());
    }

    // ======================== A plain piece is not a light ========================

    @Test
    void testLightParametersOnAPlainPieceOfACatalogLightNameAsLight() {
        HomePieceOfFurniture plain = addPlain(bulb, 10, 20);

        Response resp = handler.execute(request(plain.getId(), "lightColor", "#FFA757", "x", 77.0), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("asLight"), resp.getMessage());
        assertTrue(resp.getMessage().contains("plain piece of furniture"), resp.getMessage());
        assertSame(plain, home.getFurniture().get(0), "the piece is untouched");
        assertEquals(10f, plain.getX(), 1e-6);
        assertFalse(plain instanceof HomeLight);
    }

    @Test
    void testLightParametersOnAnOrdinaryPieceSayItIsNotALight() {
        HomePieceOfFurniture plain = addPlain(table, 10, 20);

        Response resp = handler.execute(request(plain.getId(), "lightPower", 50), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("is not a light"), resp.getMessage());
        assertFalse(resp.getMessage().contains("Pass asLight"), resp.getMessage());
    }

    @Test
    void testAsLightFalseAloneChangesNothing() {
        HomePieceOfFurniture plain = addPlain(bulb, 10, 20);

        Response resp = handler.execute(request(plain.getId(), "asLight", false), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertSame(plain, home.getFurniture().get(0));
        assertNull(resp.getData().get("lightSources"));
        assertNull(resp.getData().get("converted"));
    }

    // ======================== asLight ========================

    @Test
    void testAsLightReplacesAPlainPieceInPlaceKeepingItsState() {
        Level ground = addLevel(home, "Ground", 0, 250, 12);
        Level upstairs = addLevel(home, "Upstairs", 262, 250, 12);
        home.setSelectedLevel(ground);
        HomePieceOfFurniture before = addPlain(table, 1, 1);
        HomePieceOfFurniture plain = addPlain(bulb, 123.5f, 456.5f);
        HomePieceOfFurniture after = addPlain(table, 2, 2);
        plain.setLevel(upstairs);
        plain.setElevation(238.5f);
        plain.setAngle((float) Math.toRadians(90));
        plain.setWidth(4f);
        plain.setDepth(5f);
        plain.setHeight(6f);
        plain.setName("Kitchen can 1 light source (Hue)");
        plain.setDescription("a description");
        plain.setColor(0x336699);
        plain.setVisible(false);
        plain.setModelMirrored(true);
        plain.setProperty("ha_entity", "light.kitchen_kitchen_flood_1");
        String id = plain.getId();

        Response resp = handler.execute(request(id, "asLight", true), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(Boolean.TRUE, resp.getData().get("converted"));
        assertEquals(3, home.getFurniture().size());
        assertSame(before, home.getFurniture().get(0));
        assertSame(after, home.getFurniture().get(2));
        HomePieceOfFurniture now = home.getFurniture().get(1);
        assertTrue(now instanceof HomeLight, "the piece is a light now");
        assertNotSame(plain, now);
        assertEquals(1, home.getFurniture().stream().filter(p -> p.getId().equals(id)).count(), "its id exactly once");
        assertEquals(id, now.getId());
        assertSame(upstairs, now.getLevel(), "level kept, not the selected one");
        assertEquals(123.5f, now.getX(), 1e-6);
        assertEquals(456.5f, now.getY(), 1e-6);
        assertEquals(238.5f, now.getElevation(), 1e-6);
        assertEquals(Math.toRadians(90), now.getAngle(), 1e-4);
        assertEquals(4f, now.getWidth(), 1e-6);
        assertEquals(5f, now.getDepth(), 1e-6);
        assertEquals(6f, now.getHeight(), 1e-6);
        assertEquals("Kitchen can 1 light source (Hue)", now.getName());
        assertEquals("a description", now.getDescription());
        assertEquals(Integer.valueOf(0x336699), now.getColor());
        assertFalse(now.isVisible());
        assertTrue(now.isModelMirrored());
        assertEquals("light.kitchen_kitchen_flood_1", now.getProperty("ha_entity"));
        assertEquals("test#bulb", now.getCatalogId());
        // the catalog light's own sources and the default power
        HomeLight light = (HomeLight) now;
        assertEquals(1, light.getLightSources().length);
        assertEquals(0xD0D0D0, light.getLightSources()[0].getColor());
        assertEquals(0.5f, light.getPower(), 1e-6);
        assertEquals(1, sources(resp).size());
        assertEquals("#D0D0D0", sources(resp).get(0).get("color"));
    }

    @Test
    void testAsLightWithColorAndPowerInOneCall() {
        HomePieceOfFurniture plain = addPlain(bulb, 10, 20);

        Response resp = handler.execute(request(plain.getId(), "asLight", true, "lightColor", "#FFA757",
                "lightPower", 30, "x", 11.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeLight light = (HomeLight) home.getFurniture().get(0);
        assertEquals(0xFFA757, light.getLightSources()[0].getColor());
        assertEquals(0.5f, light.getLightSources()[0].getX(), 1e-6, "positions are the catalog's");
        assertEquals(0.3f, light.getPower(), 1e-6);
        assertEquals(11f, light.getX(), 1e-6);
        assertEquals(Boolean.TRUE, resp.getData().get("converted"));
    }

    @Test
    void testAsLightOnALightIsANoOp() {
        HomePieceOfFurniture plain = addPlain(bulb, 10, 20);
        handler.execute(request(plain.getId(), "asLight", true), accessor);
        HomePieceOfFurniture light = home.getFurniture().get(0);
        assertTrue(light instanceof HomeLight);

        Response resp = handler.execute(request(plain.getId(), "asLight", true), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertSame(light, home.getFurniture().get(0));
        assertEquals(1, home.getFurniture().size());
        assertNull(resp.getData().get("converted"));
        assertNotNull(resp.getData().get("lightSources"));
    }

    @Test
    void testAsLightOnAPieceThatIsNotACatalogLightIsRefused() {
        HomePieceOfFurniture plain = addPlain(table, 10, 20);

        Response resp = handler.execute(request(plain.getId(), "asLight", true), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not a catalog light"), resp.getMessage());
        assertSame(plain, home.getFurniture().get(0));
    }

    @Test
    void testAsLightOnAPieceWithoutCatalogIdIsRefused() {
        HomePieceOfFurniture imported = new HomePieceOfFurniture(
                new CatalogPieceOfFurniture("Imported", null, null, 10f, 10f, 10f, true, false));
        imported.setCatalogId(null);
        home.addPieceOfFurniture(imported);

        Response resp = handler.execute(request(imported.getId(), "asLight", true), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("no catalogId"), resp.getMessage());
        assertSame(imported, home.getFurniture().get(0));
    }

    @Test
    void testAsLightOnAGroupIsRefused() {
        HomePieceOfFurniture a = addPlain(bulb, 10, 20);
        HomePieceOfFurniture b = addPlain(bulb, 30, 40);
        home.deletePieceOfFurniture(a);
        home.deletePieceOfFurniture(b);
        HomeFurnitureGroup group = new HomeFurnitureGroup(Arrays.asList(a, b), "Two cans");
        home.addPieceOfFurniture(group);

        Response resp = handler.execute(request(group.getId(), "asLight", true), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("ungroup"), resp.getMessage());
        assertSame(group, home.getFurniture().get(0));
    }

    @Test
    void testAnUnconvertiblePieceIsRefusedAndNothingChanges() {
        // The piece is texturable and coloured; the catalog's light of the same id is not, so a colour cannot be kept
        CatalogLight strict = new CatalogLight("test#strict", "Strict light", null, null, null, null,
                10f, 10f, 10f, 0f, true,
                new LightSource[]{new LightSource(0.5f, 0.5f, 0.5f, 0xFFFFFF)}, null, IDENTITY, null,
                true, true, false, null, null, null);
        FurnitureCatalog catalog = new FurnitureCatalog();
        catalog.add(new FurnitureCategory("Lights"), strict);
        HomeAccessor strictAccessor = createAccessorWithCatalog(home, catalog);
        HomePieceOfFurniture plain = new HomePieceOfFurniture(new CatalogPieceOfFurniture("test#strict",
                "Strict piece", null, null, null, 10f, 10f, 10f, 0f, true, IDENTITY, null, true, null, null));
        plain.setColor(0x123456);
        plain.setX(40);
        home.addPieceOfFurniture(plain);

        Response resp = handler.execute(request(plain.getId(), "asLight", true, "x", 99.0), strictAccessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("cannot become a light"), resp.getMessage());
        assertEquals(1, home.getFurniture().size());
        assertSame(plain, home.getFurniture().get(0));
        assertEquals(40f, plain.getX(), 1e-6);
        assertEquals(Integer.valueOf(0x123456), plain.getColor());
    }

    @Test
    void testAsLightKeepsTheSelectedLevelOfTheHome() {
        Level ground = addLevel(home, "Ground", 0, 250, 12);
        Level upstairs = addLevel(home, "Upstairs", 262, 250, 12);
        home.setSelectedLevel(ground);
        HomePieceOfFurniture plain = addPlain(bulb, 10, 20);
        plain.setLevel(upstairs);

        handler.execute(request(plain.getId(), "asLight", true), accessor);

        assertSame(upstairs, home.getFurniture().get(0).getLevel());
        assertSame(ground, home.getSelectedLevel());
    }

    // ======================== get_state and query_state ========================

    @Test
    @SuppressWarnings("unchecked")
    void testGetStateReportsLightsOnly() {
        HomeLight light = addLight(10, 20);
        light.setPower(0.3f);
        HomePieceOfFurniture plain = addPlain(bulb, 30, 40);

        Response resp = new GetStateHandler().execute(new Request("get_state", new LinkedHashMap<>()), accessor);

        assertTrue(resp.isOk());
        Map<String, Object> lightItem = null;
        Map<String, Object> plainItem = null;
        for (Object o : (List<Object>) resp.getData().get("furniture")) {
            Map<String, Object> item = (Map<String, Object>) o;
            if (light.getId().equals(item.get("id"))) lightItem = item;
            if (plain.getId().equals(item.get("id"))) plainItem = item;
        }
        assertNotNull(lightItem);
        assertEquals(30.0, ((Number) lightItem.get("lightPower")).doubleValue(), 1e-6);
        List<Map<String, Object>> sources = (List<Map<String, Object>>) lightItem.get("lightSources");
        assertEquals(1, sources.size());
        assertEquals("#D0D0D0", sources.get(0).get("color"));
        assertEquals(0.5, ((Number) sources.get(0).get("x")).doubleValue(), 1e-6);
        assertEquals(0.25, ((Number) sources.get(0).get("y")).doubleValue(), 1e-6);
        assertEquals(0.75, ((Number) sources.get(0).get("z")).doubleValue(), 1e-6);
        assertEquals(0.4, ((Number) sources.get(0).get("diameter")).doubleValue(), 1e-6);
        assertNotNull(plainItem);
        assertFalse(plainItem.containsKey("lightPower"));
        assertFalse(plainItem.containsKey("lightSources"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testQueryStateFieldsPickTheLightKeys() {
        HomeLight light = addLight(10, 20);
        HomePieceOfFurniture plain = addPlain(bulb, 30, 40);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("kinds", Collections.singletonList("furniture"));
        params.put("fields", Arrays.asList("name", "lightSources", "lightPower"));

        Response resp = new QueryStateHandler().execute(new Request("query_state", params), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        List<Map<String, Object>> furniture = (List<Map<String, Object>>) resp.getData().get("furniture");
        assertEquals(2, furniture.size());
        for (Map<String, Object> item : furniture) {
            if (light.getId().equals(item.get("id"))) {
                assertTrue(item.containsKey("lightSources"));
                assertTrue(item.containsKey("lightPower"));
                assertFalse(item.containsKey("x"));
            } else {
                assertEquals(plain.getId(), item.get("id"));
                assertFalse(item.containsKey("lightSources"));
                assertFalse(item.containsKey("lightPower"));
            }
        }
    }

    // ======================== Descriptor ========================

    @Test
    @SuppressWarnings("unchecked")
    void testSchemaDescribesTheLightParameters() {
        Map<String, Object> props = (Map<String, Object>) handler.getSchema().get("properties");

        assertEquals("string", ((Map<String, Object>) props.get("lightColor")).get("type"));
        assertEquals("number", ((Map<String, Object>) props.get("lightPower")).get("type"));
        assertEquals("boolean", ((Map<String, Object>) props.get("asLight")).get("type"));
        Map<String, Object> sources = (Map<String, Object>) props.get("lightSources");
        assertEquals("array", sources.get("type"));
        assertEquals(1, sources.get("minItems"));
        Map<String, Object> item = (Map<String, Object>) sources.get("items");
        assertEquals(Arrays.asList("x", "y", "z", "color"), item.get("required"));
        assertTrue(((Map<String, Object>) item.get("properties")).containsKey("diameter"));
        assertEquals(Collections.singletonList("id"), handler.getSchema().get("required"));
        assertTrue(handler.getDescription().contains("asLight"));
    }

    @Test
    void testNoModifiablePropertiesMessageListsTheLightKeys() {
        HomePieceOfFurniture plain = addPlain(table, 0, 0);

        Response resp = handler.execute(request(plain.getId()), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("lightColor, lightSources, lightPower, asLight"), resp.getMessage());
    }

    // ======================== Helpers ========================

    private HomeLight addLight(float x, float y) {
        HomeLight light = new HomeLight(bulb);
        light.setX(x);
        light.setY(y);
        home.addPieceOfFurniture(light);
        return light;
    }

    private HomePieceOfFurniture addPlain(CatalogPieceOfFurniture catalogPiece, float x, float y) {
        HomePieceOfFurniture piece = new HomePieceOfFurniture(catalogPiece);
        piece.setX(x);
        piece.setY(y);
        home.addPieceOfFurniture(piece);
        return piece;
    }

    private static Map<String, Object> source(double x, double y, double z, String color, Double diameter) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("x", x);
        s.put("y", y);
        s.put("z", z);
        s.put("color", color);
        if (diameter != null) {
            s.put("diameter", diameter);
        }
        return s;
    }

    private static Request request(String id, Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Request("modify_furniture", params);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sources(Response resp) {
        return (List<Map<String, Object>>) resp.getData().get("lightSources");
    }
}
