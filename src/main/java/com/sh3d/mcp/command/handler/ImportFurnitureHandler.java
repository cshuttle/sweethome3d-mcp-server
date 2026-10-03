package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.FormatUtil;
import com.sh3d.mcp.command.util.ModelImporter;

import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Level;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.command.util.SchemaBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for "import_furniture".
 * Imports a 3D model file as a new piece, copying the model into the home the way the
 * Import furniture wizard does (see {@link ModelImporter}), so it is saved inside the
 * .sh3d and needs no source file afterwards.
 */
public class ImportFurnitureHandler implements CommandHandler, CommandDescriptor {

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String filePath = request.getString("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return Response.error("Parameter 'filePath' is required");
        }
        String name = request.getString("name");
        if (name == null || name.trim().isEmpty()) {
            return Response.error("Parameter 'name' is required");
        }
        Map<String, Object> params = request.getParams();
        if (!params.containsKey("x")) {
            return Response.error("Missing required parameter: x");
        }
        if (!params.containsKey("y")) {
            return Response.error("Missing required parameter: y");
        }
        float x = request.getFloat("x");
        float y = request.getFloat("y");
        float angle = request.getFloat("angle", 0f);
        float elevation = request.getFloat("elevation", 0f);
        Boolean movableParam = request.getBoolean("movable");
        boolean movable = movableParam != null && movableParam;

        Float width = optionalFloat(request, "width");
        Float depth = optionalFloat(request, "depth");
        Float height = optionalFloat(request, "height");
        if ((width != null && width <= 0) || (depth != null && depth <= 0)
                || (height != null && height <= 0)) {
            return Response.error("width, depth and height must be positive");
        }

        float[][] rotation;
        try {
            rotation = ModelImporter.parseRotation(params.get("modelRotation"));
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }

        String levelRef = request.getString("level");
        Level[] level = new Level[1];
        if (levelRef != null) {
            String error = accessor.runOnEDT(() -> {
                List<Level> matches = findLevels(accessor.getHome(), levelRef);
                if (matches.size() == 1) {
                    level[0] = matches.get(0);
                    return null;
                }
                return matches.isEmpty() ? "Level not found: " + levelRef
                        : "Several levels are named '" + levelRef + "'; pass the level id instead";
            });
            if (error != null) {
                return Response.error(error);
            }
        }

        ModelImporter.ImportedModel model;
        try {
            model = ModelImporter.importModel(filePath, rotation);
        } catch (IOException e) {
            return Response.error(e.getMessage());
        }
        float[] size = ModelImporter.fitSize(model, width, depth, height);

        HomePieceOfFurniture placed = accessor.runOnEDT(() -> {
            // Same constructor and defaults as ImportedFurnitureWizardController.finish()
            CatalogPieceOfFurniture catalogPiece = new CatalogPieceOfFurniture(
                    name, null, model.content, size[0], size[1], size[2], 0f, movable, null, null,
                    rotation, model.modelFlags, model.modelSize, null,
                    (float) Math.PI / 8, (float) -Math.PI / 16, 1f, true);
            HomePieceOfFurniture piece = new HomePieceOfFurniture(catalogPiece);
            piece.setX(x);
            piece.setY(y);
            piece.setElevation(elevation);
            piece.setAngle((float) Math.toRadians(angle));
            Home home = accessor.getHome();
            home.addPieceOfFurniture(piece);
            if (level[0] != null) {
                piece.setLevel(level[0]);
            }
            return piece;
        });

        Map<String, Object> data = FormatUtil.buildFurnitureInfo(placed);
        data.put("level", placed.getLevel() != null ? placed.getLevel().getName() : null);
        data.put("movable", placed.isMovable());
        return Response.ok(data);
    }

    /** Returns the number, or null when the parameter is absent or null. */
    static Float optionalFloat(Request request, String key) {
        return request.getParams().get(key) == null ? null : request.getFloat(key);
    }

    /** Levels whose id equals the reference, else whose name equals it (ignoring case). */
    static List<Level> findLevels(Home home, String ref) {
        List<Level> matches = new ArrayList<>();
        Level byId = ObjectResolver.findLevel(home, ref);
        if (byId != null) {
            matches.add(byId);
            return matches;
        }
        for (Level level : home.getLevels()) {
            if (ref.equalsIgnoreCase(level.getName())) {
                matches.add(level);
            }
        }
        return matches;
    }

    @Override
    public String getDescription() {
        return "Imports a 3D model file (OBJ with its MTL and textures beside it, DAE, 3DS, or a ZIP "
                + "holding one) as a new piece of furniture. The model is copied into the home like "
                + "Sweet Home 3D's Import furniture wizard does, so it is saved inside the .sh3d, keeps "
                + "its materials and textures, and needs no source file afterwards. Every import gets a "
                + "new content URL, so a re-exported file with the same name never shows a cached shape. "
                + "Omitted width/depth/height use the model's natural size (model units read as cm); "
                + "when only some are given the others keep the model's proportions. "
                + "Returns the piece id for modify_furniture, replace_model and delete_furniture.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredString("filePath", "Absolute path to the model file (.obj, .dae, .3ds, .lws, or .zip)")
                .requiredString("name", "Name of the new piece")
                .requiredNumber("x", "X coordinate of the piece center in cm")
                .requiredNumber("y", "Y coordinate of the piece center in cm")
                .numberWithDefault("elevation", "Elevation above the level floor in cm", 0)
                .number("width", "Width in cm (default: natural size, or proportional to the given dimensions)")
                .number("depth", "Depth in cm (default: natural size, or proportional to the given dimensions)")
                .number("height", "Height in cm (default: natural size, or proportional to the given dimensions)")
                .numberWithDefault("angle", "Rotation angle in degrees", 0)
                .string("level", "Level name or id (default: the selected level)")
                .raw("modelRotation", modelRotationSchema())
                .boolWithDefault("movable", "Whether the piece is movable", false)
                .build();
    }

    /** Schema of the modelRotation parameter, shared with replace_model. */
    static Map<String, Object> modelRotationSchema() {
        Map<String, Object> choice = new LinkedHashMap<>();
        choice.put("type", "string");
        choice.put("enum", Arrays.asList("yUp", "zUp"));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", "array");
        row.put("items", Collections.singletonMap("type", "number"));
        row.put("minItems", 3);
        row.put("maxItems", 3);
        Map<String, Object> matrix = new LinkedHashMap<>();
        matrix.put("type", "array");
        matrix.put("items", row);
        matrix.put("minItems", 3);
        matrix.put("maxItems", 3);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("description", "Model orientation: 'yUp' (default; OBJ convention and the Import "
                + "furniture wizard's default), 'zUp' (rotates -90 degrees around X), or a 3x3 rotation "
                + "matrix given as three rows");
        schema.put("oneOf", Arrays.asList(choice, matrix));
        return schema;
    }
}
