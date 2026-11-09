package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.FormatUtil;
import com.sh3d.mcp.command.util.ColorParser;
import com.sh3d.mcp.command.util.LightUtil;
import com.sh3d.mcp.command.util.FurnitureUtil;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeLight;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.command.util.SchemaBuilder;

import static com.sh3d.mcp.command.util.FormatUtil.colorToHex;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Обработчик команды "modify_furniture".
 * Изменяет свойства мебели по стабильному строковому ID.
 */
public class ModifyFurnitureHandler implements CommandHandler, CommandDescriptor {

    private static final List<String> MODIFIABLE_KEYS = Arrays.asList(
            "x", "y", "angle", "elevation", "width", "depth", "height",
            "color", "visible", "mirrored", "name", "dropOnTopElevation"
    );

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String id = request.getString("id");
        if (id == null) {
            return Response.error("Missing required parameter 'id'");
        }

        Map<String, Object> params = request.getParams();
        boolean hasModifiable = MODIFIABLE_KEYS.stream().anyMatch(params::containsKey)
                || params.containsKey("properties")
                || LightUtil.hasKeys(params);
        if (!hasModifiable) {
            return Response.error("No modifiable properties provided. "
                    + "Supported: x, y, angle, elevation, width, depth, height, color, visible, mirrored, name, "
                    + "properties, lightColor, lightSources, lightPower, asLight, dropOnTopElevation");
        }

        if (params.containsKey("dropOnTopElevation")) {
            Object raw = params.get("dropOnTopElevation");
            if (!(raw instanceof Number) || !Double.isFinite(((Number) raw).doubleValue())) {
                return Response.error("'dropOnTopElevation' must be a finite number "
                        + "(a fraction of the piece's height, or -1 to ignore drops)");
            }
        }

        // Validate custom properties and light parameters before EDT
        final Map<String, String> propertiesToSet;
        final LightUtil.Edit lightEdit;
        try {
            propertiesToSet = parseProperties(params);
            lightEdit = LightUtil.parse(params);
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }

        // Validate color format before EDT
        ColorParser.ColorResult colorResult = ColorParser.parseNullable(params, "color");
        if (colorResult != null && colorResult.hasError()) {
            return Response.error(colorResult.error);
        }

        final Integer colorToSet = (colorResult != null && !colorResult.clear) ? colorResult.value : null;
        final boolean doClearColor = colorResult != null && colorResult.clear;

        // The piece's data, an error message (a String), or null when the piece does not exist
        Object outcome = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            HomePieceOfFurniture piece = ObjectResolver.findFurniture(home, id);

            if (piece == null) {
                return null;
            }

            // Light: check everything first, then replace the piece by a real light (asLight) and set its sources
            // and power, so a refusal changes nothing
            boolean converted = false;
            if (lightEdit.asLight || lightEdit.changesLight()) {
                LightUtil.Prepared prepared = LightUtil.prepare(lightEdit, piece, accessor.getFurnitureCatalog());
                if (prepared.error != null) {
                    return prepared.error;
                }
                if (prepared.replacement != null) {
                    LightUtil.swap(home, piece, prepared.replacement);
                    piece = prepared.replacement;
                    converted = true;
                }
                if (piece instanceof HomeLight) {
                    LightUtil.apply(lightEdit, (HomeLight) piece);
                }
            }

            // Position
            if (params.containsKey("x")) {
                piece.setX(request.getFloat("x"));
            }
            if (params.containsKey("y")) {
                piece.setY(request.getFloat("y"));
            }
            if (params.containsKey("angle")) {
                piece.setAngle((float) Math.toRadians(request.getFloat("angle")));
            }
            if (params.containsKey("elevation")) {
                piece.setElevation(request.getFloat("elevation"));
            }

            // Dimensions
            if (params.containsKey("width")) {
                piece.setWidth(request.getFloat("width"));
            }
            if (params.containsKey("depth")) {
                piece.setDepth(request.getFloat("depth"));
            }
            if (params.containsKey("height")) {
                piece.setHeight(request.getFloat("height"));
            }

            // Appearance
            if (doClearColor) {
                piece.setColor(null);
            } else if (colorToSet != null) {
                piece.setColor(colorToSet);
            }

            // Flags
            Boolean visible = request.getBoolean("visible");
            if (visible != null) {
                piece.setVisible(visible);
            }
            Boolean mirrored = request.getBoolean("mirrored");
            if (mirrored != null) {
                piece.setModelMirrored(mirrored);
            }

            // Name
            if (params.containsKey("name")) {
                String newName = request.getString("name");
                if (newName != null) {
                    piece.setName(newName);
                }
            }

            // Custom properties (HomeObject.setProperty: saved in Home.xml as <property name value/>)
            if (propertiesToSet != null) {
                for (Map.Entry<String, String> e : propertiesToSet.entrySet()) {
                    piece.setProperty(e.getKey(), e.getValue());
                }
            }

            // Drop target: Sweet Home 3D 7.5 saves this value but has no setter for it
            if (params.containsKey("dropOnTopElevation")) {
                FurnitureUtil.setDropOnTopElevation(piece, request.getFloat("dropOnTopElevation"));
            }

            // Build response with current state
            Map<String, Object> result = FormatUtil.buildFurnitureInfo(piece);
            result.put("color", colorToHex(piece.getColor()));
            result.put("visible", piece.isVisible());
            result.put("mirrored", piece.isModelMirrored());
            result.put("properties", FormatUtil.buildProperties(piece));
            LightUtil.putLightInfo(result, piece);
            if (converted) {
                result.put("converted", true);
            }
            return result;
        });

        if (outcome == null) {
            return Response.error("Furniture not found: " + id);
        }
        if (outcome instanceof String) {
            return Response.error((String) outcome);
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) outcome;
        return Response.ok(data);
    }

    /**
     * Reads the optional 'properties' object: property name to a string value, or null to remove it.
     * Returns null when the parameter is absent.
     */
    static Map<String, String> parseProperties(Map<String, Object> params) {
        if (!params.containsKey("properties")) {
            return null;
        }
        Object raw = params.get("properties");
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("'properties' must be an object of property name to "
                    + "string value (null removes the property), e.g. {\"ha_entity\": \"light.porch\"}");
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        if (map.isEmpty()) {
            throw new IllegalArgumentException("'properties' must name at least one property");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            Object name = e.getKey();
            if (!(name instanceof String) || ((String) name).trim().isEmpty()) {
                throw new IllegalArgumentException("Property names in 'properties' must be non-empty strings");
            }
            Object value = e.getValue();
            if (value != null && !(value instanceof String)) {
                throw new IllegalArgumentException("Property '" + name + "' must be a string or null (to remove it), "
                        + "got " + value);
            }
            out.put((String) name, (String) value);
        }
        return out;
    }

    @Override
    public String getDescription() {
        return "Modifies properties of existing furniture by ID. Use get_state to find furniture IDs. "
                + "Only provided properties are changed; omitted properties remain unchanged. "
                + "Coordinates are in centimeters, angle in degrees. "
                + "Color is a hex string like '#FF0000' (red) or null to reset to default. "
                + "properties sets custom string properties on the piece ({name: value}; null removes one), "
                + "which Sweet Home 3D saves in the home and get_state/query_state report as 'properties'; "
                + "other properties are kept. "
                + "Light pieces (get_state/query_state report lightPower and lightSources for them): "
                + "lightColor '#RRGGBB' recolours every light source; lightSources [{x, y, z, color, diameter?}] "
                + "replaces them (x, y, z are fractions 0-1 of the piece's width, depth and height; diameter is a "
                + "fraction of its width); lightPower is the app's 'Light power (%)', 0-100. "
                + "A piece placed with place_furniture from a catalog light is a plain piece with no sources and "
                + "no power: asLight: true first replaces it, in place, by a real light with the same id, level, "
                + "position, size, name and properties. Sweet Home 3D then lights its 3D view and photos from it. "
                + "dropOnTopElevation sets where a piece dropped on this one lands, as a fraction of its height "
                + "(1, the default, is its top); a negative value makes the app ignore this piece as a drop target, "
                + "for a large generated piece (walls, trim, wiring) whose outline encloses others. "
                + "Undo with checkpoint / restore_checkpoint.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredString("id", "Furniture ID from get_state")
                .number("x", "New X coordinate in cm")
                .number("y", "New Y coordinate in cm")
                .number("angle", "New rotation angle in degrees (0-360)")
                .number("elevation", "Height above floor in cm")
                .number("width", "New width in cm")
                .number("depth", "New depth in cm")
                .number("height", "New height in cm")
                .nullableString("color", "Color as hex '#RRGGBB' (e.g. '#FF0000' for red), or null to reset")
                .bool("visible", "Whether furniture is visible in the scene")
                .bool("mirrored", "Whether furniture model is mirrored")
                .string("name", "New display name for the furniture")
                .raw("properties", propertiesSchema())
                .string("lightColor", "Light pieces: color of every light source as '#RRGGBB'; positions and "
                        + "diameters are kept. Not with lightSources")
                .array("lightSources", SchemaBuilder.arrayDef(
                        "Light pieces: new light sources replacing the piece's own (at least one). x, y, z are "
                                + "fractions 0-1 of the piece's width, depth and height; diameter, optional, is a "
                                + "fraction of its width (omitted: the app's 3.25 cm radius). "
                                + "Example: [{\"x\":0.5,\"y\":0.5,\"z\":0.5,\"color\":\"#FFA757\"}]")
                        .items(SchemaBuilder.create()
                                .requiredNumber("x", "Fraction 0-1 of the piece's width")
                                .requiredNumber("y", "Fraction 0-1 of the piece's depth")
                                .requiredNumber("z", "Fraction 0-1 of the piece's height")
                                .requiredString("color", "Color as '#RRGGBB'")
                                .number("diameter", "Fraction above 0 and up to 1 of the piece's width")
                                .build())
                        .minItems(1)
                        .build())
                .number("lightPower", "Light pieces: power in percent, 0-100 (the app's 'Light power (%)')")
                .bool("asLight", "Replace a plain piece of a catalog light (what place_furniture creates) by a real "
                        + "light first, keeping its id, level, position, size, name and properties. Without it the "
                        + "light parameters are refused on a plain piece")
                .number("dropOnTopElevation", "Where a dropped piece lands, as a fraction of this piece's height "
                        + "(1 = its top, the default); negative: never a drop target")
                .build();
    }

    private static Map<String, Object> propertiesSchema() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", Arrays.asList("string", "null"));
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("type", "object");
        def.put("description", "Custom properties to set, name to string value; null removes that property. "
                + "Properties not named are kept. Example: {\"ha_entity\": \"light.porch,light.porch_2\"}");
        def.put("additionalProperties", value);
        return def;
    }

}
