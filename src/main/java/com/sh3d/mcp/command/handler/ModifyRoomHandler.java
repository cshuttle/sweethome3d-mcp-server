package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.ValidationUtil;
import com.sh3d.mcp.command.util.ColorParser;
import com.sh3d.mcp.command.util.FormatUtil;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Room;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.command.util.SchemaBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Обработчик команды "modify_room".
 * Изменяет свойства комнаты по стабильному ID, включая её полигон ("points"):
 * the polygon is replaced in place with {@link Room#setPoints}, so the room keeps
 * its id, name, level, textures, colours and visibility flags.
 */
public class ModifyRoomHandler implements CommandHandler, CommandDescriptor {

    private static final List<String> MODIFIABLE_KEYS = Arrays.asList(
            "name", "points", "floorVisible", "ceilingVisible", "areaVisible",
            "floorColor", "ceilingColor",
            "floorShininess", "ceilingShininess"
    );

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String id = request.getString("id");
        if (id == null || id.trim().isEmpty()) {
            return Response.error("Missing required parameter 'id'");
        }

        Map<String, Object> params = request.getParams();
        boolean hasModifiable = MODIFIABLE_KEYS.stream().anyMatch(params::containsKey);
        if (!hasModifiable) {
            return Response.error("No modifiable properties provided. "
                    + "Supported: name, points, floorVisible, ceilingVisible, areaVisible, "
                    + "floorColor, ceilingColor, floorShininess, ceilingShininess");
        }

        // Parse and validate colors before EDT
        ColorParser.ColorResult floorColorResult = ColorParser.parseNullable(params, "floorColor");
        if (floorColorResult != null && floorColorResult.hasError()) {
            return Response.error(floorColorResult.error);
        }

        ColorParser.ColorResult ceilingColorResult = ColorParser.parseNullable(params, "ceilingColor");
        if (ceilingColorResult != null && ceilingColorResult.hasError()) {
            return Response.error(ceilingColorResult.error);
        }

        // Parse and validate the replacement polygon before EDT
        float[][] newPoints = null;
        if (params.containsKey("points")) {
            try {
                newPoints = parsePoints(params.get("points"));
            } catch (IllegalArgumentException e) {
                return Response.error(e.getMessage());
            }
        }
        final float[][] finalPoints = newPoints;

        // Validate shininess before EDT
        String shininessError = ValidationUtil.validateRange(params, 0f, 1f,
                "floorShininess", "ceilingShininess");
        if (shininessError != null) {
            return Response.error(shininessError);
        }

        // Capture for lambda
        final boolean doSetFloorColor = floorColorResult != null;
        final boolean doClearFloorColor = doSetFloorColor && floorColorResult.clear;
        final int finalFloorColor = doSetFloorColor ? floorColorResult.value : 0;
        final boolean doSetCeilingColor = ceilingColorResult != null;
        final boolean doClearCeilingColor = doSetCeilingColor && ceilingColorResult.clear;
        final int finalCeilingColor = doSetCeilingColor ? ceilingColorResult.value : 0;

        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();

            Room room = ObjectResolver.findRoom(home, id);
            if (room == null) {
                return null;
            }

            // Name
            if (params.containsKey("name")) {
                room.setName(request.getString("name"));
            }

            // Geometry (replaced in place; id, level and appearance are kept)
            if (finalPoints != null) {
                room.setPoints(finalPoints);
            }

            // Visibility
            Boolean floorVisible = request.getBoolean("floorVisible");
            if (floorVisible != null) {
                room.setFloorVisible(floorVisible);
            }
            Boolean ceilingVisible = request.getBoolean("ceilingVisible");
            if (ceilingVisible != null) {
                room.setCeilingVisible(ceilingVisible);
            }
            Boolean areaVisible = request.getBoolean("areaVisible");
            if (areaVisible != null) {
                room.setAreaVisible(areaVisible);
            }

            // Colors
            if (doSetFloorColor) {
                room.setFloorColor(doClearFloorColor ? null : finalFloorColor);
            }
            if (doSetCeilingColor) {
                room.setCeilingColor(doClearCeilingColor ? null : finalCeilingColor);
            }

            // Shininess
            if (params.containsKey("floorShininess")) {
                room.setFloorShininess(request.getFloat("floorShininess"));
            }
            if (params.containsKey("ceilingShininess")) {
                room.setCeilingShininess(request.getFloat("ceilingShininess"));
            }

            return buildResponse(id, room);
        });

        if (data == null) {
            return Response.error("Room not found: " + id);
        }

        return Response.ok(data);
    }

    private static Map<String, Object> buildResponse(String id, Room room) {
        return FormatUtil.buildRoomInfo(room);
    }

    /**
     * Parses the "points" parameter: an array of at least 3 {x, y} objects with finite
     * numeric coordinates (cm).
     *
     * @throws IllegalArgumentException with a user-facing message if the input is invalid
     */
    static float[][] parsePoints(Object pointsObj) {
        if (!(pointsObj instanceof List)) {
            throw new IllegalArgumentException("Parameter 'points' must be an array of {x, y} objects");
        }
        List<?> pointsList = (List<?>) pointsObj;
        if (pointsList.size() < 3) {
            throw new IllegalArgumentException("Parameter 'points' must contain at least 3 points, got " + pointsList.size());
        }
        float[][] polygon = new float[pointsList.size()][2];
        for (int i = 0; i < pointsList.size(); i++) {
            Object ptObj = pointsList.get(i);
            if (!(ptObj instanceof Map)) {
                throw new IllegalArgumentException("Point at index " + i + " must be an object with 'x' and 'y'");
            }
            Map<?, ?> pt = (Map<?, ?>) ptObj;
            Object xVal = pt.get("x");
            Object yVal = pt.get("y");
            if (!(xVal instanceof Number) || !(yVal instanceof Number)) {
                throw new IllegalArgumentException("Point at index " + i + " must have numeric 'x' and 'y'");
            }
            float x = ((Number) xVal).floatValue();
            float y = ((Number) yVal).floatValue();
            if (!Float.isFinite(x) || !Float.isFinite(y)) {
                throw new IllegalArgumentException("Point at index " + i + " must have finite 'x' and 'y'");
            }
            polygon[i][0] = x;
            polygon[i][1] = y;
        }
        return polygon;
    }

    // --- Descriptor ---

    @Override
    public String getDescription() {
        return "Modifies properties of an existing room by ID. Use get_state to find room IDs. "
                + "Only provided properties are changed; omitted ones remain unchanged. "
                + "Colors are hex strings like '#CCBB99' (beige floor), or null to reset to default. "
                + "Shininess ranges from 0.0 (matte) to 1.0 (glossy). "
                + "'points' replaces the room's polygon in place (at least 3 {x, y} points in cm), "
                + "keeping its id, name, level, textures, colors and visibility flags.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredString("id", "Room ID from get_state")
                .string("name", "Room name (e.g. 'Kitchen', 'Living Room')")
                .array("points", SchemaBuilder.arrayDef(
                        "New polygon vertices in cm, replacing the room's current points. Minimum 3 points. "
                                + "Example: [{\"x\":0,\"y\":0},{\"x\":500,\"y\":0},{\"x\":500,\"y\":400}]")
                        .items(SchemaBuilder.create()
                                .requiredNumber("x", "X coordinate in cm")
                                .requiredNumber("y", "Y coordinate in cm")
                                .build())
                        .minItems(3)
                        .build())
                .bool("floorVisible", "Whether floor surface is visible in 3D")
                .bool("ceilingVisible", "Whether ceiling surface is visible in 3D")
                .bool("areaVisible", "Whether area label is shown on the plan")
                .nullableString("floorColor",
                        "Floor color as '#RRGGBB' (e.g. '#CCBB99' for beige), or null to reset")
                .nullableString("ceilingColor", "Ceiling color as '#RRGGBB', or null to reset")
                .number("floorShininess", "Floor shininess: 0.0 (matte) to 1.0 (glossy)")
                .number("ceilingShininess", "Ceiling shininess: 0.0 (matte) to 1.0 (glossy)")
                .build();
    }

}
