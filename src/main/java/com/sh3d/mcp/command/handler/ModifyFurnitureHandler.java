package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.FormatUtil;
import com.sh3d.mcp.command.util.ColorParser;

import com.eteks.sweethome3d.model.Home;
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
            "color", "visible", "mirrored", "name"
    );

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String id = request.getString("id");
        if (id == null) {
            return Response.error("Missing required parameter 'id'");
        }

        Map<String, Object> params = request.getParams();
        boolean hasModifiable = MODIFIABLE_KEYS.stream().anyMatch(params::containsKey)
                || params.containsKey("properties");
        if (!hasModifiable) {
            return Response.error("No modifiable properties provided. "
                    + "Supported: x, y, angle, elevation, width, depth, height, color, visible, mirrored, name, "
                    + "properties");
        }

        // Validate custom properties before EDT
        final Map<String, String> propertiesToSet;
        try {
            propertiesToSet = parseProperties(params);
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

        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            HomePieceOfFurniture piece = ObjectResolver.findFurniture(home, id);

            if (piece == null) {
                return null;
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

            // Build response with current state
            Map<String, Object> result = FormatUtil.buildFurnitureInfo(piece);
            result.put("color", colorToHex(piece.getColor()));
            result.put("visible", piece.isVisible());
            result.put("mirrored", piece.isModelMirrored());
            result.put("properties", FormatUtil.buildProperties(piece));
            return result;
        });

        if (data == null) {
            return Response.error("Furniture not found: " + id);
        }

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
                + "other properties are kept. Undo with checkpoint / restore_checkpoint.";
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
