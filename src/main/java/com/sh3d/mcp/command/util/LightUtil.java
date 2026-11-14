package com.sh3d.mcp.command.util;

import com.eteks.sweethome3d.j3d.ModelManager;
import com.eteks.sweethome3d.model.CatalogLight;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.FurnitureCatalog;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.model.Light;
import com.eteks.sweethome3d.model.LightSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Light support for {@code modify_furniture}, {@code get_state} and {@code query_state}.
 *
 * <p>Sweet Home 3D models a light as a {@link HomeLight}: a piece of furniture that carries
 * {@link LightSource}s (position as a fraction of the piece's width, depth and height, an RGB colour,
 * an optional diameter as a fraction of the width) and a power between 0 and 1 (the Modify furniture
 * dialog's "Light power (%)"). The app lights its 3D view with them and
 * {@code PhotoRenderer} emits light from them. {@code place_furniture} creates a plain
 * {@link HomePieceOfFurniture} even for a catalog light, so a "light source" placed by it has no sources
 * and no power; {@link #prepare} replaces such a piece by a real light when asked to. Any other piece, such as a
 * generated model with no catalog id, can become a light too: the light is then built from the piece itself, with
 * no point sources and Sweet Home 3D's default power.
 *
 * <p>A light shines either from its points or through its <em>glowing materials</em>: materials of its own
 * model, named in {@link HomeLight#getLightSourceMaterialNames()} (Sweet Home 3D's "light source materials"),
 * whose whole surface gives off the light. {@code PhotoRenderer} makes those materials emit and adds no point
 * light for such a light. Sweet Home 3D saves them in Home.xml ({@code <lightSourceMaterial name='…'/>} under
 * {@code <light>}).
 */
public final class LightUtil {

    public static final String LIGHT_COLOR = "lightColor";
    public static final String LIGHT_SOURCES = "lightSources";
    public static final String LIGHT_POWER = "lightPower";
    public static final String AS_LIGHT = "asLight";
    public static final String LIGHT_SOURCE_MATERIAL_NAMES = "lightSourceMaterialNames";

    /** The {@code modify_furniture} parameters this class handles. */
    public static final List<String> KEYS = Arrays.asList(LIGHT_COLOR, LIGHT_SOURCES, LIGHT_POWER, AS_LIGHT,
            LIGHT_SOURCE_MATERIAL_NAMES);

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
        /** Replace a plain piece by a real light first (from its catalog light, or from the piece itself). */
        public final boolean asLight;
        /** New glowing materials (names of the piece's model's materials; empty clears them), or null. */
        public final String[] materialNames;

        private Edit(Integer color, LightSource[] sources, Float power, boolean asLight, String[] materialNames) {
            this.color = color;
            this.sources = sources;
            this.power = power;
            this.asLight = asLight;
            this.materialNames = materialNames;
        }

        /** True when the request carries any light parameter, including {@code asLight: false}. */
        public boolean isPresent() {
            return changesLight() || asLight;
        }

        /** True when the request changes a light's sources, power or glowing materials. */
        public boolean changesLight() {
            return color != null || sources != null || power != null || materialNames != null;
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
        String[] materialNames = null;
        if (params.containsKey(LIGHT_SOURCE_MATERIAL_NAMES)) {
            materialNames = parseMaterialNames(params.get(LIGHT_SOURCE_MATERIAL_NAMES));
        }
        return new Edit(color, sources, power, asLight, materialNames);
    }

    /** A list of non-blank names, duplicates dropped, order kept; an empty list is valid (it clears them). */
    private static String[] parseMaterialNames(Object raw) {
        String expected = "Parameter '" + LIGHT_SOURCE_MATERIAL_NAMES + "' must be an array of material names "
                + "of the piece's model (strings; an empty array clears them), got ";
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException(expected + raw);
        }
        Set<String> names = new LinkedHashSet<>();
        for (Object name : (List<?>) raw) {
            if (!(name instanceof String) || ((String) name).trim().isEmpty()) {
                throw new IllegalArgumentException(expected + raw);
            }
            names.add((String) name);
        }
        return names.toArray(new String[0]);
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

    /**
     * Adds {@code lightPower} (percent), {@code lightSources} and {@code lightSourceMaterialNames} (the glowing
     * materials, an empty list when there are none) to {@code info} when {@code piece} is a light.
     */
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
        String[] materialNames = light.getLightSourceMaterialNames();
        info.put(LIGHT_SOURCE_MATERIAL_NAMES,
                materialNames != null ? new ArrayList<>(Arrays.asList(materialNames)) : new ArrayList<>());
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
                    ? " (catalog item '" + piece.getCatalogId() + "' is not a catalog light)" : "")
                    + "; pass asLight: true (in the same call) to make it a light first, built from the piece itself "
                    + "with no point sources and the default power");
        }
        if (piece instanceof HomeFurnitureGroup) {
            return fail(what + " is a group; ungroup it first and convert its pieces");
        }
        if (piece.isDoorOrWindow()) {
            return fail(what + " is a door or window; it cannot become a light");
        }
        HomeLight replacement;
        try {
            // A catalog light gives its own sources and power; any other piece becomes a light of its own
            replacement = convert(piece, catalogLight != null ? catalogLight : new PieceLight(piece));
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
        if (edit.materialNames != null && edit.materialNames.length > 0) {
            String what = "Light '" + light.getName() + "' (" + light.getId() + ")";
            Set<String> modelNames;
            try {
                modelNames = modelMaterialNames(light);
            } catch (IOException | RuntimeException e) {
                return fail(what + ": cannot read its model's materials to check '" + LIGHT_SOURCE_MATERIAL_NAMES
                        + "': " + e.getMessage());
            }
            List<String> unknown = new ArrayList<>();
            for (String name : edit.materialNames) {
                if (!modelNames.contains(name)) {
                    unknown.add("'" + name + "'");
                }
            }
            if (!unknown.isEmpty()) {
                return fail(what + ": " + String.join(", ", unknown) + (unknown.size() == 1 ? " is not a material"
                        : " are not materials") + " of its model. Its model's materials: "
                        + (modelNames.isEmpty() ? "none" : String.join(", ", modelNames)));
            }
        }
        return new Prepared(null, replacement);
    }

    /**
     * The names of the materials of {@code piece}'s own model, sorted: the Java 3D appearance names Sweet Home 3D
     * gave them when it loaded the model (an OBJ's {@code newmtl} names), read with {@link ModelManager} as the
     * Modify furniture dialog's material list does. {@code PhotoRenderer} matches a light's glowing material names
     * against these same appearance names.
     *
     * @throws IOException when the piece has no model or Sweet Home 3D cannot load it
     */
    static Set<String> modelMaterialNames(HomePieceOfFurniture piece) throws IOException {
        if (piece.getModel() == null) {
            throw new IOException("the piece has no model");
        }
        ModelManager manager = ModelManager.getInstance();
        Set<String> names = new TreeSet<>();
        for (HomeMaterial material : manager.getMaterials(manager.loadModel(piece.getModel()))) {
            if (material.getName() != null) {
                names.add(material.getName());
            }
        }
        return names;
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
     * Builds the light that replaces a plain piece: {@code source}'s sources, glowing materials and power, with the
     * same id and everything else copied from the piece, so only its class changes. {@code source} is the piece's
     * catalog light, or a {@link PieceLight} of the piece itself. The flags a piece takes from its catalog item
     * and that have no setter (resizable, deformable, texturable, horizontally rotatable) come from {@code source}.
     *
     * @throws IllegalStateException when a value cannot be copied (e.g. a colour on a piece that is not texturable)
     */
    static HomeLight convert(HomePieceOfFurniture piece, Light source) {
        HomeLight light = new HomeLight(piece.getId(), source);
        light.setCatalogId(piece.getCatalogId());
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
     * A piece seen as a light of its own, to build a {@link HomeLight} from with {@link #convert}: every property
     * is the piece's, with no point sources, no glowing materials and (from {@link HomeLight}'s constructor)
     * Sweet Home 3D's default power. This is how a piece that came from no catalog light (a generated or imported
     * model) becomes a light: {@code HomeLight}'s constructor takes a {@link Light}, and reading the piece through
     * this view keeps even the catalog-only flags (deformable, width/depth deformable, texturable) that have no
     * setters.
     */
    static final class PieceLight implements Light {
        private final HomePieceOfFurniture piece;

        PieceLight(HomePieceOfFurniture piece) {
            this.piece = piece;
        }

        @Override public LightSource[] getLightSources() { return new LightSource[0]; }
        @Override public String[] getLightSourceMaterialNames() { return new String[0]; }

        @Override public String getName() { return piece.getName(); }
        @Override public String getDescription() { return piece.getDescription(); }
        @Override public String getInformation() { return piece.getInformation(); }
        @Override public String getLicense() { return piece.getLicense(); }
        @Override public float getDepth() { return piece.getDepth(); }
        @Override public float getHeight() { return piece.getHeight(); }
        @Override public float getWidth() { return piece.getWidth(); }
        @Override public float getElevation() { return piece.getElevation(); }
        @Override public float getDropOnTopElevation() { return piece.getDropOnTopElevation(); }
        @Override public boolean isMovable() { return piece.isMovable(); }
        @Override public boolean isDoorOrWindow() { return piece.isDoorOrWindow(); }
        @Override public Content getIcon() { return piece.getIcon(); }
        @Override public Content getPlanIcon() { return piece.getPlanIcon(); }
        @Override public Content getModel() { return piece.getModel(); }
        @Override public int getModelFlags() { return piece.getModelFlags(); }
        @Override public Long getModelSize() { return piece.getModelSize(); }
        @Override public float[][] getModelRotation() { return piece.getModelRotation(); }
        @Override public String getStaircaseCutOutShape() { return piece.getStaircaseCutOutShape(); }
        @Override public String getCreator() { return piece.getCreator(); }
        @Override public boolean isBackFaceShown() { return piece.isBackFaceShown(); }
        @Override public Integer getColor() { return piece.getColor(); }
        @Override public boolean isResizable() { return piece.isResizable(); }
        @Override public boolean isDeformable() { return piece.isDeformable(); }
        @Override public boolean isWidthDepthDeformable() { return piece.isWidthDepthDeformable(); }
        @Override public boolean isTexturable() { return piece.isTexturable(); }
        @Override public boolean isHorizontallyRotatable() { return piece.isHorizontallyRotatable(); }
        @Override public BigDecimal getPrice() { return piece.getPrice(); }
        @Override public BigDecimal getValueAddedTaxPercentage() { return piece.getValueAddedTaxPercentage(); }
        @Override public String getCurrency() { return piece.getCurrency(); }
        @Override public String getProperty(String name) { return piece.getProperty(name); }
        @Override public Collection<String> getPropertyNames() { return piece.getPropertyNames(); }
        @Override public Content getContentProperty(String name) { return piece.getContentProperty(name); }
        @Override public boolean isContentProperty(String name) { return piece.isContentProperty(name); }
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
        if (edit.materialNames != null) {
            light.setLightSourceMaterialNames(edit.materialNames);
        }
    }
}
