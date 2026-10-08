package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.CommandHandler;

import com.eteks.sweethome3d.model.CatalogDoorOrWindow;
import com.eteks.sweethome3d.model.CatalogTexture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeDoorOrWindow;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Label;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Sash;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.addFurniture;
import static com.sh3d.mcp.command.handler.TestFixtures.addLevel;
import static com.sh3d.mcp.command.handler.TestFixtures.addRoom;
import static com.sh3d.mcp.command.handler.TestFixtures.addWall;
import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A two-level home:
 * <pre>
 *   Ground:   wall (0,0)-(500,0); room Kitchen 0..400 x 0..300; Kitchen table at (200,150);
 *             hidden Hidden lamp at (450,250); door Front door at (100,0); label "Kitchen note" at (50,50)
 *   Upstairs: wall (0,0)-(0,400); room Bedroom 0..400 x 0..400; Bed at (200,200); label "Bed note" at (300,300)
 * </pre>
 */
class QueryStateHandlerTest {

    private QueryStateHandler handler;
    private Home home;
    private HomeAccessor accessor;
    private Level ground;
    private Level upstairs;
    private HomePieceOfFurniture table;

    @BeforeEach
    void setUp() {
        handler = new QueryStateHandler();
        home = new Home();
        accessor = createAccessor(home);
        ground = addLevel(home, "Ground", 0, 250, 12);
        upstairs = addLevel(home, "Upstairs", 262, 250, 12);
        home.setSelectedLevel(ground);

        on(ground, addWall(home, 0, 0, 500, 0));
        Room kitchen = addRoom(home, 0, 0, 400, 300);
        kitchen.setName("Kitchen");
        on(ground, kitchen);
        table = addFurniture(home, "Kitchen table", 200, 150);
        on(ground, table);
        HomePieceOfFurniture lamp = addFurniture(home, "Hidden lamp", 450, 250);
        lamp.setVisible(false);
        on(ground, lamp);
        HomeDoorOrWindow door = new HomeDoorOrWindow(new CatalogDoorOrWindow(
                "test#door", "Front door", null, null, null,
                90f, 10f, 210f, 0f, false, 1f, 0f, new Sash[0], null, null, true, null, null));
        door.setX(100);
        door.setY(0);
        home.addPieceOfFurniture(door);
        on(ground, door);
        Label note = new Label("Kitchen note", 50, 50);
        home.addLabel(note);
        on(ground, note);

        on(upstairs, addWall(home, 0, 0, 0, 400));
        Room bedroom = addRoom(home, 0, 0, 400, 400);
        bedroom.setName("Bedroom");
        on(upstairs, bedroom);
        on(upstairs, addFurniture(home, "Bed", 200, 200));
        Label bedNote = new Label("Bed note", 300, 300);
        home.addLabel(bedNote);
        on(upstairs, bedNote);
    }

    private static void on(Level level, Object item) {
        if (item instanceof Wall) ((Wall) item).setLevel(level);
        if (item instanceof Room) ((Room) item).setLevel(level);
        if (item instanceof HomePieceOfFurniture) ((HomePieceOfFurniture) item).setLevel(level);
        if (item instanceof Label) ((Label) item).setLevel(level);
    }

    // --- Descriptor ---

    @Test
    void testImplementsInterfaces() {
        assertTrue(handler instanceof CommandHandler);
        assertTrue(handler instanceof CommandDescriptor);
        assertFalse(handler.getDescription().isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSchemaHasAllFilters() {
        Map<String, Object> props = (Map<String, Object>) handler.getSchema().get("properties");
        for (String p : Arrays.asList("kinds", "level", "bbox", "name", "fields", "visible", "limit")) {
            assertTrue(props.containsKey(p), p);
        }
        assertTrue(((List<?>) handler.getSchema().get("required")).isEmpty());
    }

    // --- No filters ---

    @Test
    void testNoFiltersReturnsEveryKind() {
        Response resp = execute();
        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(2, list(resp, "levels").size());
        assertEquals(2, list(resp, "walls").size());
        assertEquals(2, list(resp, "rooms").size());
        assertEquals(Arrays.asList("Kitchen table", "Hidden lamp", "Bed"), names(resp, "furniture"));
        assertEquals(Collections.singletonList("Front door"), names(resp, "doors"));
        assertEquals(2, list(resp, "labels").size());
        assertEquals(false, resp.getData().get("truncated"));
        assertEquals(3, matched(resp, "furniture"));
    }

    @Test
    void testItemsAreSerialisedLikeGetState() {
        Response state = new GetStateHandler().execute(new Request("get_state", Collections.emptyMap()), accessor);
        Map<String, Object> fromState = null;
        for (Map<String, Object> item : list(state, "furniture")) {
            if (table.getId().equals(item.get("id"))) fromState = item;
        }
        Response query = execute("kinds", list("furniture"), "name", "^kitchen table$");
        assertEquals(Collections.singletonList(fromState), list(query, "furniture"));

        assertEquals(list(state, "walls"), list(execute("kinds", list("walls")), "walls"));
        assertEquals(list(state, "rooms"), list(execute("kinds", list("rooms")), "rooms"));
        assertEquals(list(state, "labels"), list(execute("kinds", list("labels")), "labels"));
        assertEquals(list(state, "levels"), list(execute("kinds", list("levels")), "levels"));
    }

    // --- kinds ---

    @Test
    void testKindsLimitTheResult() {
        Response resp = execute("kinds", list("rooms", "doors"));
        assertTrue(resp.isOk());
        assertTrue(resp.getData().containsKey("rooms"));
        assertTrue(resp.getData().containsKey("doors"));
        assertFalse(resp.getData().containsKey("walls"));
        assertFalse(resp.getData().containsKey("furniture"));
        assertFalse(resp.getData().containsKey("levels"));
    }

    @Test
    void testKindsAsCommaSeparatedString() {
        Response resp = execute("kinds", "walls, labels");
        assertTrue(resp.isOk());
        assertTrue(resp.getData().containsKey("walls"));
        assertTrue(resp.getData().containsKey("labels"));
        assertFalse(resp.getData().containsKey("rooms"));
    }

    @Test
    void testUnknownKindIsAnError() {
        Response resp = execute("kinds", list("windows"));
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("windows"));
    }

    // --- level ---

    @Test
    void testLevelByNameIgnoresCase() {
        Response resp = execute("level", "upstairs");
        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(Collections.singletonList("Bed"), names(resp, "furniture"));
        assertEquals(Collections.singletonList("Bedroom"), names(resp, "rooms"));
        assertEquals(1, list(resp, "walls").size());
        assertTrue(list(resp, "doors").isEmpty());
        assertEquals(Collections.singletonList("Upstairs"), names(resp, "levels"));
    }

    @Test
    void testLevelById() {
        Response resp = execute("level", ground.getId(), "kinds", list("furniture"));
        assertEquals(Arrays.asList("Kitchen table", "Hidden lamp"), names(resp, "furniture"));
    }

    @Test
    void testUnknownLevelIsAnError() {
        Response resp = execute("level", "Attic");
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("Attic"));
    }

    // --- bbox ---

    @Test
    void testBboxKeepsWhatIntersects() {
        Response resp = execute("bbox", list(150, 100, 250, 200), "level", "Ground");
        assertTrue(resp.isOk(), resp.getMessage());
        assertEquals(Collections.singletonList("Kitchen table"), names(resp, "furniture"));
        assertEquals(Collections.singletonList("Kitchen"), names(resp, "rooms"));
        assertTrue(list(resp, "walls").isEmpty());
        assertTrue(list(resp, "labels").isEmpty());
        assertTrue(list(resp, "doors").isEmpty());
        // levels have no plan position: bbox does not filter them
        assertEquals(1, list(resp, "levels").size());
    }

    @Test
    void testBboxCatchesAWallByItsThickness() {
        // The wall runs along y=0 with thickness 10, so it covers y -5..5.
        Response resp = execute("bbox", list(300, 3, 310, 20), "kinds", list("walls"));
        assertEquals(1, list(resp, "walls").size());
        Response miss = execute("bbox", list(300, 6, 310, 20), "kinds", list("walls"));
        assertTrue(list(miss, "walls").isEmpty());
    }

    @Test
    void testBboxCornersInAnyOrderAndAsAPoint() {
        Response swapped = execute("bbox", list(250, 200, 150, 100), "level", "Ground", "kinds", list("furniture"));
        assertEquals(Collections.singletonList("Kitchen table"), names(swapped, "furniture"));
        Response point = execute("bbox", list(200, 150, 200, 150), "level", "Ground", "kinds", list("furniture"));
        assertEquals(Collections.singletonList("Kitchen table"), names(point, "furniture"));
    }

    @Test
    void testBboxOnLabelsUsesTheirPoint() {
        Response resp = execute("bbox", list(40, 40, 60, 60), "kinds", list("labels"));
        assertEquals(Collections.singletonList("Kitchen note"), texts(resp));
    }

    @Test
    void testBadBboxIsAnError() {
        assertTrue(execute("bbox", list(0, 0, 10)).isError());
        assertTrue(execute("bbox", Arrays.asList(0, "a", 1, 2)).isError());
        assertTrue(execute("bbox", "0,0,1,1").isError());
    }

    // --- name ---

    @Test
    void testNameIsACaseInsensitiveRegex() {
        Response resp = execute("name", "KITCHEN");
        assertEquals(Collections.singletonList("Kitchen table"), names(resp, "furniture"));
        assertEquals(Collections.singletonList("Kitchen"), names(resp, "rooms"));
        assertEquals(Collections.singletonList("Kitchen note"), texts(resp));
        assertTrue(list(resp, "walls").isEmpty(), "walls have no name");
        assertTrue(list(resp, "levels").isEmpty());

        Response regex = execute("name", "^(bed|front)", "kinds", list("furniture", "doors", "rooms"));
        assertEquals(Collections.singletonList("Bed"), names(regex, "furniture"));
        assertEquals(Collections.singletonList("Front door"), names(regex, "doors"));
        assertEquals(Collections.singletonList("Bedroom"), names(regex, "rooms"));
    }

    @Test
    void testBadRegexIsAnError() {
        Response resp = execute("name", "(unclosed");
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("regex"));
    }

    // --- visible ---

    @Test
    void testVisibleFilter() {
        assertEquals(Collections.singletonList("Hidden lamp"),
                names(execute("visible", false, "kinds", list("furniture")), "furniture"));
        assertEquals(Arrays.asList("Kitchen table", "Bed"),
                names(execute("visible", true, "kinds", list("furniture")), "furniture"));
    }

    @Test
    void testItemsOnAHiddenLevelAreNotVisible() {
        upstairs.setViewable(false);
        Response hidden = execute("visible", false);
        assertEquals(Arrays.asList("Hidden lamp", "Bed"), names(hidden, "furniture"));
        assertEquals(Collections.singletonList("Bedroom"), names(hidden, "rooms"));
        assertEquals(1, list(hidden, "walls").size());
        assertEquals(Collections.singletonList("Upstairs"), names(hidden, "levels"));
    }

    // --- fields ---

    @Test
    void testFieldsKeepOnlyWhatIsAskedPlusId() {
        Response resp = execute("kinds", list("furniture"), "fields", list("name", "x", "noSuchField"));
        for (Map<String, Object> item : list(resp, "furniture")) {
            assertEquals(Arrays.asList("id", "name", "x"), new ArrayList<>(item.keySet()));
        }
    }

    @Test
    void testTextureOffsetsAreReportedInCentimetres() {
        Wall wall = home.getWalls().iterator().next();
        wall.setLeftSideTexture(new HomeTexture(new CatalogTexture("Stone veneer", null, 60f, 40f),
                0f, 0.5f, 0f, 1f, false, true));
        Room kitchen = home.getRooms().get(0);
        kitchen.setFloorTexture(new HomeTexture(new CatalogTexture("Oak", null, 40f, 40f),
                0.25f, 0f, 0f, 2f, false, true));

        Response resp = execute("kinds", list("walls", "rooms"),
                "fields", list("leftSideTexture", "leftSideTextureInfo", "rightSideTextureInfo",
                        "floorTexture", "floorTextureInfo"));

        Map<String, Object> w = byId(list(resp, "walls"), wall.getId());
        assertEquals("Stone veneer", w.get("leftSideTexture"));
        @SuppressWarnings("unchecked")
        Map<String, Object> wallInfo = (Map<String, Object>) w.get("leftSideTextureInfo");
        assertEquals(20.0, wallInfo.get("yOffset"));
        assertEquals(0.0, wallInfo.get("xOffset"));
        assertTrue(w.containsKey("rightSideTextureInfo"));
        assertNull(w.get("rightSideTextureInfo"));

        Map<String, Object> r = byId(list(resp, "rooms"), kitchen.getId());
        @SuppressWarnings("unchecked")
        Map<String, Object> floorInfo = (Map<String, Object>) r.get("floorTextureInfo");
        assertEquals("Oak", floorInfo.get("name"));
        assertEquals(20.0, floorInfo.get("xOffset"));
        assertEquals(2.0, floorInfo.get("scale"));
    }

    private static Map<String, Object> byId(List<Map<String, Object>> items, String id) {
        for (Map<String, Object> item : items) {
            if (id.equals(item.get("id"))) return item;
        }
        fail("no item " + id);
        return null;
    }

    @Test
    void testFieldsMustBeStrings() {
        assertTrue(execute("fields", list(1, 2)).isError());
    }

    // --- limit ---

    @Test
    void testLimitIsPerKindAndReportsTruncation() {
        Response resp = execute("limit", 1, "kinds", list("furniture", "doors"));
        assertEquals(1, list(resp, "furniture").size());
        assertEquals(3, matched(resp, "furniture"));
        assertEquals(1, list(resp, "doors").size());
        assertEquals(true, resp.getData().get("truncated"));
    }

    @Test
    void testBadLimitIsAnError() {
        assertTrue(execute("limit", 0).isError());
        assertTrue(execute("limit", 1.5).isError());
        assertTrue(execute("limit", "ten").isError());
    }

    // --- Combined filters ---

    @Test
    void testFiltersCombine() {
        Response resp = execute("kinds", list("furniture"), "level", "Ground", "visible", true,
                "bbox", list(0, 0, 500, 300), "fields", list("name"));
        assertEquals(1, list(resp, "furniture").size());
        assertEquals("Kitchen table", list(resp, "furniture").get(0).get("name"));
    }

    // --- Helpers ---

    private Response execute(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return handler.execute(new Request("query_state", params), accessor);
    }

    private static List<Object> list(Object... values) {
        return Arrays.asList(values);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Response resp, String kind) {
        assertTrue(resp.isOk(), resp.getMessage());
        return (List<Map<String, Object>>) resp.getData().get(kind);
    }

    private static List<Object> names(Response resp, String kind) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> item : list(resp, kind)) out.add(item.get("name"));
        return out;
    }

    private static List<Object> texts(Response resp) {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> item : list(resp, "labels")) out.add(item.get("text"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static int matched(Response resp, String kind) {
        return (Integer) ((Map<String, Object>) resp.getData().get("matched")).get(kind);
    }
}
