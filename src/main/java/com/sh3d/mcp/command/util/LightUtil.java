package com.sh3d.mcp.command.util;

import com.eteks.sweethome3d.model.CatalogLight;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.FurnitureCatalog;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.LightSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Light support for {@code modify_furniture}, {@code get_state} and {@code query_state}.
 *
 * <p>Sweet Home 3D models a light as a {@link HomeLight}: a piece of furniture that carries
 * {@link LightSource}s (position as a fraction of the piece's width, depth and height, an RGB colour,
 * an optional diameter as a fraction of the width) and a power between 0 and 1 (the Modify furniture
 * dialog's "Light power (%)"). The app lights its 3D view with them and
 * {@code PhotoRenderer} emits light from them. {@code place_furniture} creates a plain
 * {@link HomePieceOfFurniture} even for a catalog light, so a "light source" placed by it has no sources
 * and no power; {@link #prepare} replaces such a piece by a real light when asked to.
 */
public final class LightUtil {

    public static final String LIGHT_COLOR = "lightColor";
    public static final String LIGHT_SOURCES = "lightSources";
    public static final String LIGHT_POWER = "lightPower";
    public static final String AS_LIGHT = "asLight";

    /** The {@code modify_furniture} parameters this class handles. */
    public static final List<String> KEYS = Arrays.asList(LIGHT_COLOR, LIGHT_SOURCES, LIGHT_POWER, AS_LIGHT);

    private static final List<String> SOURCE_FIELDS = Arrays.asList("x", "y", "z", "color", "diameter");

    private LightUtil() {
    }

    // ======================== Request parameters ========================

    /** The light parameters of one {@code modify_furniture} request, validated but not applied. */
    public static final class Edit {
        /** New colour (0xRRGGBB) of every source, or null. */
        public final Integer color;
        /** New sources replacing the piece's own, or null. */
        public final LightSource[] sources;
        /** New power, 0..1, or null. */
        public final Float power;
        /** Replace a plain piece of a catalog light by a real light first. */
        public final boolean asLight;

        private Edit(Integer color, LightSource[] sources, Float power, boolean asLight) {
            this.color = color;
            this.sources = sources;
            this.power = power;
            this.asLight = asLight;
        }

        /** True when the request carries any light parameter, including {@code asLight: false}. */
        public boolean isPresent() {
            return color != null || sources != null || power != null || asLight;
        }

        /** True when the request changes a light's sources or power. */
        public boolean changesLight() {
            return color != null || sources != null || power != null;
        }
    }

    /** True when {@code params} names any light parameter. */
    public static boolean hasKeys(Map<String, Object> params) {
        for (String key : KEYS) {
            if (params.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates the light parameters of a request without looking at any piece.
     *
     * @throws IllegalArgumentException with a user-facing message when a value is invalid
     */
    public static Edit parse(Map<String, Object> params) {
        Integer color = null;
        if (params.containsKey(LIGHT_COLOR)) {
            color = parseColor(params.get(LIGHT_COLOR), "Parameter '" + LIGHT_COLOR + "'");
        }
        LightSource[] sources = null;
        if (params.containsKey(LIGHT_SOURCES)) {
            sources = parseSources(params.get(LIGHT_SOURCES));
        }
        if (color != null && sources != null) {
            throw new IllegalArgumentException("Give either '" + LIGHT_COLOR + "' (recolours every source) or '"
                    + LIGHT_SOURCES + "' (replaces them, each with its own color), not both");
        }
        Float power = null;
        if (params.containsKey(LIGHT_POWER)) {
            Object raw = params.get(LIGHT_POWER);
            if (!(raw instanceof Number) || !Double.isFinite(((Number) raw).doubleValue())) {
                throw new IllegalArgumentException("Parameter '" + LIGHT_POWER
                        + "' must be a number between 0 and 100 (percent), got " + raw);
            }
            double percent = ((Number) raw).doubleValue();
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("Parameter '" + LIGHT_POWER
                        + "' must be between 0 and 100 (percent), got " + percent);
            }
            power = (float) (percent / 100.0);
        }
        boolean asLight = false;
        if (params.containsKey(AS_LIGHT)) {
            Object raw = params.get(AS_LIGHT);
            if (!(raw instanceof Boolean)) {
                throw new IllegalArgumentException("Parameter '" + AS_LIGHT + "' must be true or false, got " + raw);
            }
            asLight = (Boolean) raw;
        }
        return new Edit(color, sources, power, asLight);
    }

    private static Integer parseColor(Object raw, String what) {
        Integer parsed = raw instanceof String ? FormatUtil.parseHexColor((String) raw) : null;
        if (parsed == null) {
            throw new IllegalArgumentException(what + " must be a color like '#FFA757', got " + raw);
        }
        return parsed;
    }

    private static LightSource[] parseSources(Object raw) {
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            throw new IllegalArgumentException("Parameter '" + LIGHT_SOURCES
                    + "' must be a non-empty array of {x, y, z, color, diameter?} objects");
        }
        List<?> list = (List<?>) raw;
        LightSource[] sources = new LightSource[list.size()];
        for (int i = 0; i < sources.length; i++) {
            String at = "Light source at index " + i;
            if (!(list.get(i) instanceof Map)) {
                throw new IllegalArgumentException(at + " must be an object with x, y, z and color");
            }
            Map<?, ?> map = (Map<?, ?>) list.get(i);
            for (Object key : map.keySet()) {
                if (!SOURCE_FIELDS.contains(key)) {
                    throw new IllegalArgumentException(at + " has unknown field '" + key
                            + "'; allowed: " + String.join(", ", SOURCE_FIELDS));
                }
            }
            float x = fraction(map, "x", at);
            float y = fraction(map, "y", at);
            float z = fraction(map, "z", at);
            if (!map.containsKey("color")) {
                throw new IllegalArgumentException(at + " needs a 'color' like '#FFA757'");
            }
            int color = parseColor(map.get("color"), at + " 'color'");
            Float diameter = null;
            Object d = map.get("diameter");
            if (d != null) {
                if (!(d instanceof Number) || !Double.isFinite(((Number) d).doubleValue())
                        || ((Number) d).doubleValue() <= 0 || ((Number) d).doubleValue() > 1) {
                    throw new IllegalArgumentException(at + " 'diameter' must be a number above 0 and at most 1 "
                            + "(a fraction of the piece's width), or omitted, got " + d);
                }
                diameter = ((Number) d).floatValue();
            }
            sources[i] = new LightSource(x, y, z, color, diameter);
        }
        return sources;
    }

    private static float fraction(Map<?, ?> map, String key, String at) {
        Object v = map.get(key);
        if (!(v instanceof Number) || !Double.isFinite(((Number) v).doubleValue())
                || ((Number) v).doubleValue() < 0 || ((Number) v).doubleValue() > 1) {
            throw new IllegalArgumentException(at + " '" + key + "' must be a number from 0 to 1 "
                    + "(a fraction of the piece's " + ("x".equals(key) ? "width" : "y".equals(key) ? "depth" : "height")
                    + "), got " + v);
        }
        return ((Number) v).floatValue();
    }

    // ======================== Reporting ========================

    /** Adds {@code lightPower} (percent) and {@code lightSources} to {@code info} when {@code piece} is a light. */
    public static void putLightInfo(Map<String, Object> info, HomePieceOfFurniture piece) {
        if (!(piece instanceof HomeLight)) {
            return;
        }
        HomeLight light = (HomeLight) piece;
        info.put("lightPower", FormatUtil.round2(light.getPower() * 100.0));
        List<Object> sources = new ArrayList<>();
        for (LightSource source : light.getLightSources()) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("x", round4(source.getX()));
            s.put("y", round4(source.getY()));
            s.put("z", round4(source.getZ()));
            s.put("color", FormatUtil.colorToHex(source.getColor()));
            s.put("diameter", source.getDiameter() != null ? round4(source.getDiameter()) : null);
            sources.add(s);
        }
        info.put("lightSources", sources);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }

    // ======================== Checking and converting ========================

    /** What {@link #prepare} decided: an error, or the light to put in place of a plain piece (null: none needed). */
    public static final class Prepared {
        public final String error;
        /** The replacement light, fully built from the piece but not in the home yet; null when none is needed. */
        public final HomeLight replacement;

        private Prepared(String error, HomeLight replacement) {
            this.error = error;
            this.replacement = replacement;
        }
    }

    /**
     * Checks a light edit against a piece and, for {@code asLight}, builds the light that replaces it.
     * Changes nothing: neither the piece nor the home.
     *
     * @param catalog the furniture catalog, used to find the catalog light a plain piece came from
     */
    public static Prepared prepare(Edit edit, HomePieceOfFurniture piece, FurnitureCatalog catalog) {
        if (piece instanceof HomeLight) {
            return check(edit, (HomeLight) piece, null);
        }
        String what = "Furniture '" + piece.getName() + "' (" + piece.getId() + ")";
        CatalogLight catalogLight = catalogLightOf(piece, catalog);
        if (!edit.asLight) {
            if (catalogLight != null) {
                return fail(what + " is a plain piece of furniture, not a light, although its catalog item '"
                        + piece.getCatalogId() + "' is a light (place_furniture creates plain pieces). "
                        + "Pass asLight: true to replace it by a real light first; the app then lights its 3D view "
                        + "and photos from it");
            }
            return fail(what + " is not a light" + (piece.getCatalogId() != null
                    ? " (catalog item '" + piece.getCatalogId() + "' is not a catalog light)" : ""));
        }
        if (piece instanceof HomeFurnitureGroup) {
            return fail(what + " is a group; ungroup it first and convert its pieces");
        }
        if (catalogLight == null) {
            return fail(what + " cannot become a light: "
                    + (piece.getCatalogId() == null
                    ? "it has no catalogId to find a catalog light by"
                    : "catalog item '" + piece.getCatalogId() + "' is not a catalog light or not in the catalog"));
        }
        HomeLight replacement;
        try {
            replacement = convert(piece, catalogLight);
        } catch (IllegalStateException e) {
            return fail(what + " cannot become a light: " + e.getMessage());
        }
        return check(edit, replacement, replacement);
    }

    /** Checks the edit against the light it will be applied to; {@code replacement} is passed on when there is one. */
    private static Prepared check(Edit edit, HomeLight light, HomeLight replacement) {
        if (edit.color != null && light.getLightSources().length == 0) {
            return fail("Light '" + light.getName() + "' (" + light.getId() + ") has no light sources to recolour; "
                    + "pass '" + LIGHT_SOURCES + "' instead");
        }
        return new Prepared(null, replacement);
    }

    private static Prepared fail(String message) {
        return new Prepared(message, null);
    }

    /** The catalog light {@code piece} came from, or null when it came from none. */
    private static CatalogLight catalogLightOf(HomePieceOfFurniture piece, FurnitureCatalog catalog) {
        String catalogId = piece.getCatalogId();
        if (catalogId == null || catalogId.trim().isEmpty() || catalog == null) {
            return null;
        }
        CatalogSearchUtil.FurnitureSearchResult result = CatalogSearchUtil.findFurniture(catalog, null, catalogId, null);
        CatalogPieceOfFurniture found = result.isFound() ? result.getFound() : null;
        return found instanceof CatalogLight ? (CatalogLight) found : null;
    }

    /**
     * Builds the light that replaces a plain piece: the catalog light's own sources and power, with the same id
     * and everything else copied from the piece, so only its class changes. The catalog's
     * {@code dropOnTopElevation} is kept (Sweet Home 3D 7.5 has no setter for it).
     *
     * @throws IllegalStateException when a value cannot be copied (e.g. a colour on a piece that is not texturable)
     */
    static HomeLight convert(HomePieceOfFurniture piece, CatalogLight catalogLight) {
        HomeLight light = new HomeLight(piece.getId(), catalogLight);
        light.setName(piece.getName());
        light.setNameVisible(piece.isNameVisible());
        light.setNameXOffset(piece.getNameXOffset());
        light.setNameYOffset(piece.getNameYOffset());
        light.setNameAngle(piece.getNameAngle());
        light.setNameStyle(piece.getNameStyle());
        light.setDescription(piece.getDescription());
        light.setInformation(piece.getInformation());
        light.setCreator(piece.getCreator());
        light.setLicense(piece.getLicense());
        light.setPrice(piece.getPrice());
        light.setValueAddedTaxPercentage(piece.getValueAddedTaxPercentage());
        light.setCurrency(piece.getCurrency());

        // The model, as the piece has it now (replace_model may have changed it)
        light.setIcon(piece.getIcon());
        light.setPlanIcon(piece.getPlanIcon());
        light.setModel(piece.getModel());
        light.setModelSize(piece.getModelSize());
        light.setModelRotation(piece.getModelRotation());
        light.setModelFlags(piece.getModelFlags());
        light.setModelCenteredAtOrigin(piece.isModelCenteredAtOrigin());
        light.setModelTransformations(piece.getModelTransformations());
        light.setStaircaseCutOutShape(piece.getStaircaseCutOutShape());
        light.setModelMirrored(piece.isModelMirrored());

        // Place and size
        light.setX(piece.getX());
        light.setY(piece.getY());
        light.setElevation(piece.getElevation());
        light.setAngle(piece.getAngle());
        if (piece.getPitch() != 0) {
            light.setPitch(piece.getPitch());
        }
        if (piece.getRoll() != 0) {
            light.setRoll(piece.getRoll());
        }
        if (piece.getWidth() != light.getWidth()) {
            light.setWidth(piece.getWidth());
        }
        if (piece.getDepth() != light.getDepth()) {
            light.setDepth(piece.getDepth());
        }
        if (piece.getHeight() != light.getHeight()) {
            light.setHeight(piece.getHeight());
        }
        light.setWidthInPlan(piece.getWidthInPlan());
        light.setDepthInPlan(piece.getDepthInPlan());
        light.setHeightInPlan(piece.getHeightInPlan());
        light.setMovable(piece.isMovable());
        light.setVisible(piece.isVisible());
        FurnitureUtil.setDropOnTopElevation(light, piece.getDropOnTopElevation());

        // Appearance
        if (piece.getColor() != null) {
            light.setColor(piece.getColor());
        }
        if (piece.getTexture() != null) {
            light.setTexture(piece.getTexture());
        }
        if (piece.getShininess() != null) {
            light.setShininess(piece.getShininess());
        }
        if (piece.getModelMaterials() != null) {
            light.setModelMaterials(piece.getModelMaterials());
        }

        // Custom properties (ha_entity and the like)
        for (String name : piece.getPropertyNames()) {
            Object value = piece.isContentProperty(name) ? piece.getContentProperty(name) : piece.getProperty(name);
            if (value != null) {
                light.setProperty(name, value);
            }
        }
        return light;
    }

    /**
     * Puts {@code replacement} in the home in place of {@code piece}: same index in the furniture list and
     * same level. {@link Home#addPieceOfFurniture(HomePieceOfFurniture, int)} files a piece on the selected level
     * and {@link Home#deletePieceOfFurniture} clears the level of the piece it removes, so the level is read
     * first and set last.
     */
    public static void swap(Home home, HomePieceOfFurniture piece, HomeLight replacement) {
        Level level = piece.getLevel();
        int index = home.getFurniture().indexOf(piece);
        home.deletePieceOfFurniture(piece);
        home.addPieceOfFurniture(replacement, index < 0 ? home.getFurniture().size() : index);
        replacement.setLevel(level);
    }

    /** Applies the light parameters of {@code edit} to {@code light}; {@link #prepare} has accepted them. */
    public static void apply(Edit edit, HomeLight light) {
        if (edit.sources != null) {
            light.setLightSources(edit.sources);
        } else if (edit.color != null) {
            LightSource[] old = light.getLightSources();
            LightSource[] recoloured = new LightSource[old.length];
            for (int i = 0; i < old.length; i++) {
                recoloured[i] = new LightSource(old[i].getX(), old[i].getY(), old[i].getZ(), edit.color,
                        old[i].getDiameter());
            }
            light.setLightSources(recoloured);
        }
        if (edit.power != null) {
            light.setPower(edit.power);
        }
    }
}
