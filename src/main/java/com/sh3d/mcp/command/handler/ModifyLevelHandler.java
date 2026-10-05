package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;

import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Level;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.command.util.SchemaBuilder;

import static com.sh3d.mcp.command.util.FormatUtil.round2;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler for "modify_level": changes a level's name, elevation, wall height or floor thickness in place, by stable ID.
 * Omitted properties stay as they are.
 */
public class ModifyLevelHandler implements CommandHandler, CommandDescriptor {

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String id = request.getString("id");
        if (id == null || id.trim().isEmpty()) {
            return Response.error("Missing required parameter 'id'");
        }
        String name = request.getString("name");
        float elevation = request.getFloat("elevation", Float.NaN);
        float height = request.getFloat("height", Float.NaN);
        float floorThickness = request.getFloat("floorThickness", Float.NaN);

        if (name != null && name.trim().isEmpty()) {
            return Response.error("Parameter 'name' must not be empty");
        }
        if (!Float.isNaN(elevation) && Float.isInfinite(elevation)) {
            return Response.error("Parameter 'elevation' must be finite");
        }
        if (!Float.isNaN(height) && (Float.isInfinite(height) || height <= 0)) {
            return Response.error("Parameter 'height' must be positive, got " + height);
        }
        if (!Float.isNaN(floorThickness) && (Float.isInfinite(floorThickness) || floorThickness < 0)) {
            return Response.error("Parameter 'floorThickness' must be non-negative, got " + floorThickness);
        }

        Map<String, Object> data = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();
            Level level = ObjectResolver.findLevel(home, id);
            if (level == null) {
                return null;
            }
            if (name != null) level.setName(name.trim());
            if (!Float.isNaN(elevation)) level.setElevation(elevation);
            if (!Float.isNaN(height)) level.setHeight(height);
            if (!Float.isNaN(floorThickness)) level.setFloorThickness(floorThickness);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", id);
            result.put("name", level.getName());
            result.put("elevation", round2(level.getElevation()));
            result.put("height", round2(level.getHeight()));
            result.put("floorThickness", round2(level.getFloorThickness()));
            result.put("viewable", level.isViewable());
            return result;
        });

        if (data == null) {
            return Response.error("Level not found: " + id);
        }
        return Response.ok(data);
    }

    @Override
    public String getDescription() {
        return "Modifies an existing level by its ID: name, elevation (cm), height (wall height on the level, cm) and "
                + "floorThickness (the slab under the level, cm). Only provided properties change. "
                + "Use list_levels or get_state to find level IDs. Undo with checkpoint / restore_checkpoint.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredString("id", "Level ID from list_levels or get_state")
                .string("name", "New level name")
                .number("elevation", "New bottom elevation of the level in cm")
                .number("height", "New wall height on this level in cm (must be positive)")
                .number("floorThickness", "New floor/ceiling slab thickness in cm (non-negative)")
                .build();
    }

}
