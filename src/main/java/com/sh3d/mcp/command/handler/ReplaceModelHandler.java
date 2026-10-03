package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.FormatUtil;
import com.sh3d.mcp.command.util.ModelImporter;

import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.command.util.SchemaBuilder;

import java.io.IOException;
import java.util.Map;

/**
 * Handler for "replace_model".
 * Swaps the 3D model of an existing piece in place with a model file, copied into the
 * home with a new content URL like import_furniture does (see {@link ModelImporter}).
 */
public class ReplaceModelHandler implements CommandHandler, CommandDescriptor {

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String id = request.getString("id");
        if (id == null || id.trim().isEmpty()) {
            return Response.error("Missing required parameter 'id'");
        }
        String filePath = request.getString("filePath");
        if (filePath == null || filePath.trim().isEmpty()) {
            return Response.error("Parameter 'filePath' is required");
        }
        Boolean keepSizeParam = request.getBoolean("keepSize");
        boolean keepSize = keepSizeParam == null || keepSizeParam;

        float[][] rotation;
        try {
            rotation = ModelImporter.parseRotation(request.getParams().get("modelRotation"));
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }

        // Check the piece before the (slower) model copy
        String pieceError = accessor.runOnEDT(() -> checkPiece(
                ObjectResolver.findFurniture(accessor.getHome(), id), id, keepSize));
        if (pieceError != null) {
            return Response.error(pieceError);
        }

        ModelImporter.ImportedModel model;
        try {
            model = ModelImporter.importModel(filePath, rotation);
        } catch (IOException e) {
            return Response.error(e.getMessage());
        }

        Object result = accessor.runOnEDT(() -> {
            HomePieceOfFurniture piece = ObjectResolver.findFurniture(accessor.getHome(), id);
            String error = checkPiece(piece, id, keepSize);
            if (error != null) {
                return error;
            }
            if (!keepSize) {
                float[] size = ModelImporter.fitSize(model, piece.getWidth(), null, null);
                piece.setDepth(size[1]);
                piece.setHeight(size[2]);
            }
            // The old materials and transformations name parts of the old model
            piece.setModelMaterials(null);
            piece.setModelTransformations(null);
            piece.setModelRotation(rotation);
            piece.setModelFlags(model.modelFlags);
            piece.setModelSize(model.modelSize);
            piece.setModel(model.content);
            return FormatUtil.buildFurnitureInfo(piece);
        });

        if (result instanceof String) {
            return Response.error((String) result);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result;
        return Response.ok(data);
    }

    private static String checkPiece(HomePieceOfFurniture piece, String id, boolean keepSize) {
        if (piece == null) {
            return "Furniture not found: " + id;
        }
        if (piece instanceof HomeFurnitureGroup) {
            return "Furniture " + id + " is a group; replace the model of a piece inside it instead";
        }
        if (!keepSize && !piece.isResizable()) {
            return "Furniture " + id + " is not resizable; use keepSize true";
        }
        return null;
    }

    @Override
    public String getDescription() {
        return "Replaces the 3D model of an existing piece of furniture with a model file "
                + "(OBJ with its MTL and textures beside it, DAE, 3DS, or a ZIP holding one). The model "
                + "is copied into the home with a new content URL, so it is saved inside the .sh3d and "
                + "never shows a cached older shape. keepSize true (default) keeps the piece's width, "
                + "depth, height, position and angle; false keeps its width and position but takes "
                + "depth and height from the new model's proportions. Use get_state to find ids.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredString("id", "Furniture ID from get_state")
                .requiredString("filePath", "Absolute path to the model file (.obj, .dae, .3ds, .lws, or .zip)")
                .boolWithDefault("keepSize",
                        "Keep the current width/depth/height (true), or scale the new model's natural "
                                + "proportions to the current width (false)", true)
                .raw("modelRotation", ImportFurnitureHandler.modelRotationSchema())
                .build();
    }

}
