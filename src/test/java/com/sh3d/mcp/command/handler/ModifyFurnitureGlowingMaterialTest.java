package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.io.HomeFileRecorder;
import com.eteks.sweethome3d.model.CatalogLight;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.FurnitureCatalog;
import com.eteks.sweethome3d.model.FurnitureCategory;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.LightSource;
import com.eteks.sweethome3d.tools.URLContent;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.createAccessorWithCatalog;
import static org.junit.jupiter.api.Assertions.*;

/**
 * modify_furniture's {@code lightSourceMaterialNames}: a light's glowing materials, the materials of its own model
 * that give off its light (Sweet Home 3D's "light source materials"), and how get_state, query_state and a saved
 * home report them. The test light wears the two-material fixture model {@code two_tone.obj} ("paint" and "label").
 */
class ModifyFurnitureGlowingMaterialTest {

    private static final float[][] IDENTITY = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

    private ModifyFurnitureHandler handler;
    private Home home;
    private HomeAccessor accessor;
    private URLContent twoTone;
    private CatalogLight lamp;
    private CatalogPieceOfFurniture table;

    @BeforeEach
    void setUp() throws Exception {
        handler = new ModifyFurnitureHandler();
        home = new Home();
        twoTone = new URLContent(getClass().getResource("/models/two_tone.obj"));
        lamp = new CatalogLight("test#lamp", "Test lamp", null, null, twoTone,
                100f, 80f, 50f, 0f, true,
                new LightSource[]{new LightSource(0.5f, 0.5f, 0.5f, 0xFFFFFF)},
                IDENTITY, null, true, null, null);
        table = new CatalogPieceOfFurniture("test#table", "Test table", null, null, twoTone,
                100f, 80f, 50f, 0f, true, IDENTITY, null, true, null, null);
        FurnitureCatalog catalog = new FurnitureCatalog();
        catalog.add(new FurnitureCategory("Lights"), lamp);
        catalog.add(new FurnitureCategory("Tables"), table);
        accessor = createAccessorWithCatalog(home, catalog);
    }

    // ======================== Set, report, clear ========================

    @Test
    void testSetsTheGlowingMaterials() {
        HomeLight light = addLight();

        Request request = request(light.getId(), "lightSourceMaterialNames", list("label"));
        Response resp = handler.execute(request, accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertArrayEquals(new String[]{"label"}, light.getLightSourceMaterialNames());
        assertEquals(list("label"), resp.getData().get("lightSourceMaterialNames"));
        assertEquals(1, light.getLightSources().length, "the point sources are kept");
        assertEquals(0.5f, light.getPower(), 1e-6, "power is untouched");
        assertTrue(request.getUnrecognizedKeys().isEmpty(), "the key is read");
    }

    @Test
    void testSetsSeveralAndCombinesWithPowerAndPosition() {
        HomeLight light = addLight();

        Response resp = handler.execute(request(light.getId(), "lightSourceMaterialNames", list("paint", "label"),
                "lightPower", 20, "x", 42.0), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertArrayEquals(new String[]{"paint", "label"}, light.getLightSourceMaterialNames());
        assertEquals(0.2f, light.getPower(), 1e-6);
        assertEquals(42f, light.getX(), 1e-6);
    }

    @Test
    void testAnEmptyListClearsTheGlowingMaterials() {
        HomeLight light = addLight();
        light.setLightSourceMaterialNames(new String[]{"label"});

        Response resp = handler.execute(request(light.getId(), "lightSourceMaterialNames",
                Collections.emptyList()), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(0, light.getLightSourceMaterialNames().length);
        assertEquals(Collections.emptyList(), resp.getData().get("lightSourceMaterialNames"));
    }

    @Test
    void testAnEmptyListClearsEvenWhenTheModelCannotBeRead() {
        HomeLight light = addLight();
        light.setModel(null);
        light.setLightSourceMaterialNames(new String[]{"label"});

        Response resp = handler.execute(request(light.getId(), "lightSourceMaterialNames",
                Collections.emptyList()), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(0, light.getLightSourceMaterialNames().length);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testGetStateReportsGlowingMaterialsForEveryLightOnly() {
        HomeLight glowing = addLight();
        glowing.setLightSourceMaterialNames(new String[]{"label"});
        HomeLight pointLight = addLight();
        HomePieceOfFurniture plain = addPlain(table);

        Response resp = new GetStateHandler().execute(new Request("get_state", new LinkedHashMap<>()), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Object o : (List<Object>) resp.getData().get("furniture")) {
            Map<String, Object> item = (Map<String, Object>) o;
            byId.put((String) item.get("id"), item);
        }
        assertEquals(list("label"), byId.get(glowing.getId()).get("lightSourceMaterialNames"));
        assertEquals(Collections.emptyList(), byId.get(pointLight.getId()).get("lightSourceMaterialNames"),
                "a light without glowing materials reports an empty list");
        assertTrue(byId.get(pointLight.getId()).containsKey("lightPower"));
        Map<String, Object> plainItem = byId.get(plain.getId());
        assertFalse(plainItem.containsKey("lightSourceMaterialNames"));
        assertFalse(plainItem.containsKey("lightPower"));
        assertFalse(plainItem.containsKey("lightSources"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testQueryStateFieldsPassTheGlowingMaterialsThrough() {
        HomeLight light = addLight();
        HomePieceOfFurniture plain = addPlain(table);
        handler.execute(request(light.getId(), "lightSourceMaterialNames", list("label")), accessor);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("kinds", Collections.singletonList("furniture"));
        params.put("fields", Arrays.asList("name", "lightSourceMaterialNames"));

        Response resp = new QueryStateHandler().execute(new Request("query_state", params), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        List<Map<String, Object>> furniture = (List<Map<String, Object>>) resp.getData().get("furniture");
        assertEquals(2, furniture.size());
        for (Map<String, Object> item : furniture) {
            if (light.getId().equals(item.get("id"))) {
                assertEquals(list("label"), item.get("lightSourceMaterialNames"));
                assertFalse(item.containsKey("lightPower"), "only the fields asked for");
            } else {
                assertEquals(plain.getId(), item.get("id"));
                assertFalse(item.containsKey("lightSourceMaterialNames"));
            }
        }
    }

    // ======================== Refusals change nothing ========================

    @Test
    void testAnUnknownNameIsRefusedWithTheModelsMaterialNames() {
        HomeLight light = addLight();
        light.setX(5);
        light.setLightSourceMaterialNames(new String[]{"paint"});

        Response resp = handler.execute(request(light.getId(), "lightSourceMaterialNames", list("label", "lens"),
                "x", 700.0, "lightPower", 90), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("'lens'"), resp.getMessage());
        assertTrue(resp.getMessage().contains("label, paint"), "lists the model's materials: " + resp.getMessage());
        assertArrayEquals(new String[]{"paint"}, light.getLightSourceMaterialNames(), "nothing changed");
        assertEquals(5f, light.getX(), 1e-6);
        assertEquals(0.5f, light.getPower(), 1e-6);
    }

    @Test
    void testNamesAreRefusedWhenTheModelCannotBeRead() {
        HomeLight light = addLight();
        light.setModel(null);

        Response resp = handler.execute(request(light.getId(), "lightSourceMaterialNames", list("label")), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("materials"), resp.getMessage());
        assertEquals(0, light.getLightSourceMaterialNames().length);
    }

    @Test
    void testInvalidValuesAreRefused() {
        HomeLight light = addLight();
        light.setX(5);
        List<Object[]> bad = Arrays.asList(
                new Object[]{"label", "an array of material names"},
                new Object[]{null, "an array of material names"},
                new Object[]{Arrays.asList("label", 3), "an array of material names"},
                new Object[]{Arrays.asList("label", " "), "an array of material names"});
        for (Object[] b : bad) {
            Response resp = handler.execute(request(light.getId(), "lightSourceMaterialNames", b[0], "x", 700.0),
                    accessor);
            assertTrue(resp.isError(), String.valueOf(b[0]));
            assertTrue(resp.getMessage().contains((String) b[1]), b[0] + " -> " + resp.getMessage());
            assertEquals(5f, light.getX(), 1e-6);
            assertEquals(0, light.getLightSourceMaterialNames().length);
        }
    }

    @Test
    void testOnAPieceThatIsNotALightItSaysToConvertWithAsLight() {
        HomePieceOfFurniture plain = addPlain(table);
        plain.setX(5);

        Response resp = handler.execute(request(plain.getId(), "lightSourceMaterialNames", list("label"),
                "x", 77.0), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not a light"), resp.getMessage());
        assertTrue(resp.getMessage().contains("asLight"), resp.getMessage());
        assertSame(plain, home.getFurniture().get(0), "the piece is untouched");
        assertEquals(5f, plain.getX(), 1e-6);
    }

    @Test
    void testOnAPlainPieceOfACatalogLightItSaysToConvertWithAsLight() {
        HomePieceOfFurniture plain = addPlain(lamp);

        Response resp = handler.execute(request(plain.getId(), "lightSourceMaterialNames", list("label")), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("asLight"), resp.getMessage());
        assertFalse(home.getFurniture().get(0) instanceof HomeLight);
    }

    // ======================== With asLight ========================

    @Test
    void testAsLightAndGlowingMaterialsInOneCall() {
        HomePieceOfFurniture plain = addPlain(lamp);
        String id = plain.getId();

        Response resp = handler.execute(request(id, "asLight", true, "lightSourceMaterialNames", list("label"),
                "lightPower", 20), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        HomeLight light = (HomeLight) home.getFurniture().get(0);
        assertEquals(id, light.getId());
        assertArrayEquals(new String[]{"label"}, light.getLightSourceMaterialNames());
        assertEquals(0.2f, light.getPower(), 1e-6);
        assertEquals(Boolean.TRUE, resp.getData().get("converted"));
    }

    @Test
    void testAsLightWithAnUnknownNameChangesNothing() {
        HomePieceOfFurniture plain = addPlain(lamp);

        Response resp = handler.execute(request(plain.getId(), "asLight", true,
                "lightSourceMaterialNames", list("lens")), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("label, paint"), resp.getMessage());
        assertSame(plain, home.getFurniture().get(0), "not converted");
    }

    // ======================== Save and reload ========================

    @Test
    void testALightAndItsGlowingMaterialsSurviveASaveAndReload(@TempDir Path dir) throws Exception {
        HomeLight light = addLight();
        light.setName("Back porch step lights");
        light.setProperty("ha_entity", "light.step_lights");
        handler.execute(request(light.getId(), "lightSourceMaterialNames", list("label"), "lightPower", 20), accessor);
        String file = dir.resolve("glow.sh3d").toString();

        new HomeFileRecorder(0, false, null, false, true).writeHome(home, file);
        // Read Home.xml (preferXmlEntry), the entry Sweet Home 3D's own exporter and handler write and read
        Home reloaded = new HomeFileRecorder(0, false, null, false, true).readHome(file);

        HomePieceOfFurniture back = reloaded.getFurniture().stream()
                .filter(p -> light.getId().equals(p.getId())).findFirst().orElse(null);
        assertNotNull(back, "the light keeps its id");
        assertTrue(back instanceof HomeLight, "still a light");
        HomeLight reloadedLight = (HomeLight) back;
        assertArrayEquals(new String[]{"label"}, reloadedLight.getLightSourceMaterialNames());
        assertEquals(0.2f, reloadedLight.getPower(), 1e-6);
        assertEquals("Back porch step lights", reloadedLight.getName());
        assertEquals("light.step_lights", reloadedLight.getProperty("ha_entity"));
    }

    // ======================== Descriptor ========================

    @Test
    @SuppressWarnings("unchecked")
    void testSchemaAndDescriptionsNameTheParameter() {
        Map<String, Object> props = (Map<String, Object>) handler.getSchema().get("properties");
        Map<String, Object> names = (Map<String, Object>) props.get("lightSourceMaterialNames");

        assertNotNull(names);
        assertEquals("array", names.get("type"));
        assertEquals("string", ((Map<String, Object>) names.get("items")).get("type"));
        assertTrue(handler.getDescription().contains("lightSourceMaterialNames"));
        assertTrue(new GetStateHandler().getDescription().contains("lightSourceMaterialNames"));
        assertTrue(new QueryStateHandler().getDescription().contains("lightSourceMaterialNames"));
    }

    @Test
    void testNoModifiablePropertiesMessageNamesTheParameter() {
        HomePieceOfFurniture plain = addPlain(table);

        Response resp = handler.execute(request(plain.getId()), accessor);

        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("lightSourceMaterialNames"), resp.getMessage());
    }

    // ======================== Helpers ========================

    private HomeLight addLight() {
        HomeLight light = new HomeLight(lamp);
        home.addPieceOfFurniture(light);
        return light;
    }

    private HomePieceOfFurniture addPlain(CatalogPieceOfFurniture catalogPiece) {
        HomePieceOfFurniture piece = new HomePieceOfFurniture(catalogPiece);
        home.addPieceOfFurniture(piece);
        return piece;
    }

    private static List<String> list(String... names) {
        return Arrays.asList(names);
    }

    private static Request request(String id, Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("id", id);
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return new Request("modify_furniture", params);
    }
}
