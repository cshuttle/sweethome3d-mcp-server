package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.FormatUtil;
import com.sh3d.mcp.command.util.SchemaBuilder;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Label;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Handler for "query_state": the part of get_state a client asks for (house-model#91).
 *
 * <p>get_state returns every object (about 260 KB on the house model). query_state filters by
 * kind, level, plan rectangle, name, visibility and a field list, and serialises each matching
 * item exactly as get_state does, so the two agree field for field.
 *
 * <pre>
 * Parameters (all optional; filters combine with AND):
 *   kinds   — subset of walls, rooms, furniture, doors, levels, labels (default: all).
 *             "furniture" is every piece that is not a door or window; "doors" is doors and windows.
 *   level   — level id or name (name match ignores case); keeps items on that level.
 *   bbox    — [x0, y0, x1, y1] in plan cm; keeps items whose footprint intersects it
 *             (walls, rooms, furniture: their plan polygon; labels: their anchor point; levels: ignored).
 *   name    — case-insensitive regex, found anywhere in the name (labels: text). Walls have no name.
 *   visible — true/false: an item is visible when its own flag (furniture, doors) is set and its
 *             level is viewable; a level is visible when viewable.
 *   fields  — property names to keep on each item; "id" is always kept.
 *   limit   — maximum items returned per kind.
 * Returns: one list per requested kind, "matched" (count per kind before limit), "truncated".
 * </pre>
 */
public class QueryStateHandler implements CommandHandler, CommandDescriptor {

    static final List<String> KINDS = Arrays.asList(
            "levels", "walls", "rooms", "furniture", "doors", "labels");

    /** A zero-width or zero-height bbox still has to intersect what it touches. */
    private static final double MIN_BBOX_SIZE = 1e-3;

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        Map<String, Object> params = request.getParams();

        Set<String> kinds;
        List<String> fields;
        double[] bbox;
        Pattern namePattern;
        int limit;
        try {
            kinds = parseKinds(params.get("kinds"));
            fields = parseStringList(params.get("fields"), "fields");
            bbox = parseBbox(params.get("bbox"));
            namePattern = parseName(request.getString("name"));
            limit = parseLimit(params.get("limit"));
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }
        Boolean visible = request.getBoolean("visible");
        String levelRef = request.getString("level");
        if (levelRef != null && levelRef.trim().isEmpty()) {
            return Response.error("Parameter 'level' must not be empty");
        }

        Query query = new Query(kinds, fields, bbox, namePattern, visible, limit);
        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            if (levelRef != null) {
                query.level = findLevel(home, levelRef.trim());
                if (query.level == null) {
                    return null;
                }
            }
            return query.run(home);
        });
        if (data == null) {
            return Response.error("Level not found: '" + levelRef
                    + "' (give a level id or name from list_levels)");
        }
        return Response.ok(data);
    }

    // --- The query ---

    private static final class Query {
        final Set<String> kinds;
        final List<String> fields;
        final double[] bbox;
        final Pattern name;
        final Boolean visible;
        final int limit;
        Level level;

        final Map<String, Object> result = new LinkedHashMap<>();
        final Map<String, Object> matched = new LinkedHashMap<>();
        boolean truncated;

        Query(Set<String> kinds, List<String> fields, double[] bbox, Pattern name,
              Boolean visible, int limit) {
            this.kinds = kinds;
            this.fields = fields;
            this.bbox = bbox;
            this.name = name;
            this.visible = visible;
            this.limit = limit;
        }

        Map<String, Object> run(Home home) {
            for (String kind : KINDS) {
                if (!kinds.contains(kind)) {
                    continue;
                }
                List<Map<String, Object>> items = new ArrayList<>();
                int count = 0;
                switch (kind) {
                    case "levels":
                        for (Level l : home.getLevels()) {
                            if (keepLevel(l)) {
                                add(items, count++, () -> GetStateHandler.buildLevelInfo(l, home.getSelectedLevel()));
                            }
                        }
                        break;
                    case "walls":
                        for (Wall w : home.getWalls()) {
                            if (keep(w.getLevel(), null, true, true, w.getPoints(), null)) {
                                add(items, count++, () -> FormatUtil.buildWallInfo(w));
                            }
                        }
                        break;
                    case "rooms":
                        for (Room r : home.getRooms()) {
                            if (keep(r.getLevel(), r.getName(), false, true, r.getPoints(), null)) {
                                add(items, count++, () -> FormatUtil.buildRoomInfo(r));
                            }
                        }
                        break;
                    case "furniture":
                    case "doors":
                        boolean doors = kind.equals("doors");
                        for (HomePieceOfFurniture p : home.getFurniture()) {
                            if (p.isDoorOrWindow() == doors
                                    && keep(p.getLevel(), p.getName(), false, p.isVisible(), p.getPoints(), null)) {
                                add(items, count++, () -> GetStateHandler.buildFurniturePiece(p));
                            }
                        }
                        break;
                    case "labels":
                        for (Label lb : home.getLabels()) {
                            if (keep(lb.getLevel(), lb.getText(), false, true, null,
                                    new double[]{lb.getX(), lb.getY()})) {
                                add(items, count++, () -> GetStateHandler.buildLabelInfo(lb));
                            }
                        }
                        break;
                    default:
                        break;
                }
                result.put(kind, items);
                matched.put(kind, count);
                if (count > items.size()) {
                    truncated = true;
                }
            }
            result.put("matched", matched);
            result.put("truncated", truncated);
            return result;
        }

        /** Adds the projected item while under the limit; index is the item's 0-based match number. */
        private void add(List<Map<String, Object>> items, int index, Supplier<Map<String, Object>> item) {
            if (index < limit) {
                items.add(project(item.get()));
            }
        }

        private Map<String, Object> project(Map<String, Object> item) {
            if (fields == null) {
                return item;
            }
            Map<String, Object> kept = new LinkedHashMap<>();
            kept.put("id", item.get("id"));
            for (String f : fields) {
                if (item.containsKey(f)) {
                    kept.put(f, item.get(f));
                }
            }
            return kept;
        }

        private boolean keepLevel(Level l) {
            if (level != null && l != level) {
                return false;
            }
            if (name != null && !matchesName(l.getName())) {
                return false;
            }
            return visible == null || visible == l.isViewable();
        }

        /**
         * Applies level, name, visibility and bbox filters to one plan item.
         *
         * @param unnamed  true for items with no name (walls): a name filter never keeps them
         * @param ownFlag  the item's own visibility flag (true when it has none)
         * @param polygon  plan footprint, or null when the item is a point
         * @param point    plan anchor point when polygon is null
         */
        private boolean keep(Level itemLevel, String itemName, boolean unnamed, boolean ownFlag,
                             float[][] polygon, double[] point) {
            if (level != null && itemLevel != level) {
                return false;
            }
            if (name != null && (unnamed || !matchesName(itemName))) {
                return false;
            }
            if (visible != null) {
                boolean isVisible = ownFlag && (itemLevel == null || itemLevel.isViewable());
                if (isVisible != visible) {
                    return false;
                }
            }
            if (bbox != null) {
                return polygon != null ? intersects(polygon, bbox) : contains(bbox, point[0], point[1]);
            }
            return true;
        }

        private boolean matchesName(String value) {
            return value != null && name.matcher(value).find();
        }
    }

    // --- Geometry ---

    static boolean intersects(float[][] polygon, double[] bbox) {
        if (polygon == null || polygon.length == 0) {
            return false;
        }
        if (polygon.length < 3) {
            for (float[] p : polygon) {
                if (contains(bbox, p[0], p[1])) {
                    return true;
                }
            }
            return false;
        }
        Path2D.Double path = new Path2D.Double();
        path.moveTo(polygon[0][0], polygon[0][1]);
        for (int i = 1; i < polygon.length; i++) {
            path.lineTo(polygon[i][0], polygon[i][1]);
        }
        path.closePath();
        double w = Math.max(bbox[2] - bbox[0], MIN_BBOX_SIZE);
        double h = Math.max(bbox[3] - bbox[1], MIN_BBOX_SIZE);
        return path.intersects(bbox[0], bbox[1], w, h);
    }

    static boolean contains(double[] bbox, double x, double y) {
        return x >= bbox[0] && x <= bbox[2] && y >= bbox[1] && y <= bbox[3];
    }

    // --- Parameter parsing ---

    private static Set<String> parseKinds(Object raw) {
        List<String> list = parseStringList(raw, "kinds");
        if (list == null) {
            return new LinkedHashSet<>(KINDS);
        }
        Set<String> kinds = new LinkedHashSet<>();
        for (String k : list) {
            String kind = k.trim().toLowerCase();
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException("Unknown kind '" + k + "' in 'kinds'; expected any of " + KINDS);
            }
            kinds.add(kind);
        }
        if (kinds.isEmpty()) {
            throw new IllegalArgumentException("Parameter 'kinds' must name at least one of " + KINDS);
        }
        return kinds;
    }

    /** A JSON array of strings, or one comma-separated string; null when absent. */
    private static List<String> parseStringList(Object raw, String param) {
        if (raw == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        if (raw instanceof String) {
            for (String part : ((String) raw).split(",")) {
                if (!part.trim().isEmpty()) {
                    out.add(part.trim());
                }
            }
        } else if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (!(o instanceof String)) {
                    throw new IllegalArgumentException("Parameter '" + param + "' must be an array of strings");
                }
                if (!((String) o).trim().isEmpty()) {
                    out.add(((String) o).trim());
                }
            }
        } else {
            throw new IllegalArgumentException("Parameter '" + param + "' must be an array of strings");
        }
        return out;
    }

    private static double[] parseBbox(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List) || ((List<?>) raw).size() != 4) {
            throw new IllegalArgumentException("Parameter 'bbox' must be an array of 4 numbers [x0, y0, x1, y1] in cm");
        }
        double[] v = new double[4];
        List<?> list = (List<?>) raw;
        for (int i = 0; i < 4; i++) {
            Object o = list.get(i);
            if (!(o instanceof Number) || !Double.isFinite(((Number) o).doubleValue())) {
                throw new IllegalArgumentException("Parameter 'bbox' must be an array of 4 finite numbers [x0, y0, x1, y1]");
            }
            v[i] = ((Number) o).doubleValue();
        }
        return new double[]{Math.min(v[0], v[2]), Math.min(v[1], v[3]),
                Math.max(v[0], v[2]), Math.max(v[1], v[3])};
    }

    private static Pattern parseName(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("Parameter 'name' is not a valid regex: " + e.getDescription());
        }
    }

    private static int parseLimit(Object raw) {
        if (raw == null) {
            return Integer.MAX_VALUE;
        }
        if (!(raw instanceof Number)) {
            throw new IllegalArgumentException("Parameter 'limit' must be a positive integer");
        }
        double d = ((Number) raw).doubleValue();
        if (d < 1 || d != Math.floor(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("Parameter 'limit' must be a positive integer, got " + raw);
        }
        return d > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) d;
    }

    private static Level findLevel(Home home, String ref) {
        for (Level l : home.getLevels()) {
            if (ref.equals(l.getId())) {
                return l;
            }
        }
        for (Level l : home.getLevels()) {
            if (l.getName() != null && ref.equalsIgnoreCase(l.getName().trim())) {
                return l;
            }
        }
        return null;
    }

    // --- Descriptor ---

    @Override
    public String getDescription() {
        return "Returns only the part of the scene you ask for, serialised exactly like get_state. "
                + "Filters (all optional, combined with AND): kinds (walls, rooms, furniture, doors, levels, labels; "
                + "'furniture' excludes doors and windows, 'doors' is doors and windows), level (id or name), "
                + "bbox [x0,y0,x1,y1] in plan cm (an item matches when its plan footprint intersects it; labels by "
                + "their point), name (case-insensitive regex on the name, or the text of a label; walls have no name), "
                + "visible (own visibility flag and a viewable level), fields (properties to keep; id is always kept) "
                + "and limit (items per kind). Returns a list per kind plus 'matched' counts before the limit and "
                + "'truncated'. Prefer this to get_state on large homes.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .array("kinds", SchemaBuilder.arrayDef(
                                "Kinds to return (default: all): walls, rooms, furniture, doors, levels, labels")
                        .items(enumItems())
                        .build())
                .string("level", "Level id or name (case-insensitive); keeps items on that level")
                .array("bbox", SchemaBuilder.arrayDef(
                                "Plan rectangle [x0, y0, x1, y1] in cm; keeps items whose footprint intersects it")
                        .itemsOfType("number").minItems(4).maxItems(4).build())
                .string("name", "Case-insensitive regex found in the item's name (label text for labels)")
                .array("fields", SchemaBuilder.arrayDef(
                                "Property names to keep on each item, as get_state names them; id is always kept")
                        .itemsOfType("string").build())
                .bool("visible", "true: only visible items; false: only hidden ones")
                .integer("limit", "Maximum items returned per kind")
                .build();
    }

    private static Map<String, Object> enumItems() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "string");
        item.put("enum", KINDS);
        return item;
    }
}
