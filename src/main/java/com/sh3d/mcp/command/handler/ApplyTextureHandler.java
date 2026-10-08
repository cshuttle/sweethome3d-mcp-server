package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;
import com.sh3d.mcp.command.util.CatalogSearchUtil;
import com.sh3d.mcp.command.util.TextureUtil;

import com.eteks.sweethome3d.model.CatalogTexture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.TextureImage;
import com.eteks.sweethome3d.model.TexturesCatalog;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.ObjectResolver;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import static com.sh3d.mcp.command.util.FormatUtil.textureName;
import static com.sh3d.mcp.command.util.SchemaUtil.nullableProp;
import com.sh3d.mcp.command.util.SchemaBuilder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Handler for "apply_texture": puts a texture on a wall side (left/right) or a room surface (floor/ceiling),
 * or adjusts the one already there.
 *
 * <pre>
 * Parameters:
 *   targetType, targetId, surface — the surface(s) to texture (required); "both" = both sides / floor and ceiling
 *   Texture source, exactly one of:
 *     textureName [+ textureCategory]           — a catalog texture, or null to remove the texture
 *     fromTargetType, fromTargetId, fromSurface — copy the texture already on another surface of the home
 *                                                 (image, size, angle, scale, offsets, fittingArea)
 *     keepTexture: true                         — keep the texture already on each target surface
 *   angle (degrees), scale, xOffset, yOffset (cm) — optional; override the source's values
 *     (catalog defaults: 0, 1, 0, 0). keepTexture needs at least one of them.
 *
 * Offsets: Sweet Home 3D stores them as a fraction of one tile (see TextureUtil); the API takes cm.
 * Wall sides: +yOffset moves the pattern up (origin: elevation 0 of the home); +xOffset moves it to the right
 * as seen facing the side (left-to-right oriented textures; origin: the wall's start point).
 * Floors: +x towards plan +x, +y towards plan -y. Ceilings: +x towards plan +x, +y towards plan +y.
 *
 * Why one command and not a separate modify_texture: the three sources share the same overrides, conversion
 * and response, and "keep the texture that is there" is just another source.
 *
 * EDT: the catalog lookup runs off the EDT (thread-safe); reading the source and target textures and
 * setting the new ones run in one runOnEDT() call, so nothing changes unless every surface can be textured.
 * Undo: checkpoint / restore_checkpoint, as for the other edits.
 * </pre>
 */
public class ApplyTextureHandler implements CommandHandler, CommandDescriptor {

    private static final List<String> WALL_SURFACES = Arrays.asList("left", "right", "both");
    private static final List<String> ROOM_SURFACES = Arrays.asList("floor", "ceiling", "both");
    private static final List<String> FROM_KEYS = Arrays.asList("fromTargetType", "fromTargetId", "fromSurface");

    /** Where the texture comes from. */
    private enum Source { CATALOG, COPY, KEEP }

    /** The optional overrides; null = keep the source's value. */
    private static final class Overrides {
        Float angleRad;
        Float scale;
        Float xOffsetCm;
        Float yOffsetCm;

        boolean isEmpty() {
            return angleRad == null && scale == null && xOffsetCm == null && yOffsetCm == null;
        }
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        // --- Validate the target ---
        String targetType = request.getString("targetType");
        if (targetType == null) {
            return Response.error("Missing required parameter 'targetType'. Expected 'wall' or 'room'");
        }
        if (!"wall".equals(targetType) && !"room".equals(targetType)) {
            return Response.error("Invalid targetType: '" + targetType + "'. Expected 'wall' or 'room'");
        }

        Map<String, Object> params = request.getParams();
        String targetId = request.getString("targetId");
        if (targetId == null) {
            return Response.error("Missing required parameter 'targetId'");
        }

        String surface = request.getString("surface");
        if (surface == null) {
            return Response.error("Missing required parameter 'surface'");
        }
        String surfaceError = checkSurface(targetType, surface, true, "surface");
        if (surfaceError != null) {
            return Response.error(surfaceError);
        }

        // --- Exactly one texture source ---
        boolean hasName = params.containsKey("textureName");
        boolean hasFrom = false;
        for (String key : FROM_KEYS) {
            hasFrom |= params.containsKey(key);
        }
        boolean keep = Boolean.TRUE.equals(request.getBoolean("keepTexture"));
        int sources = (hasName ? 1 : 0) + (hasFrom ? 1 : 0) + (keep ? 1 : 0);
        if (sources == 0) {
            return Response.error("Missing texture source: pass textureName (a name from list_textures_catalog, "
                    + "or null to remove the texture), fromTargetType/fromTargetId/fromSurface (copy the texture "
                    + "of another surface), or keepTexture=true (adjust the texture already there)");
        }
        if (sources > 1) {
            return Response.error("Pass only one texture source: textureName, "
                    + "fromTargetType/fromTargetId/fromSurface, or keepTexture=true");
        }
        Source source = hasName ? Source.CATALOG : hasFrom ? Source.COPY : Source.KEEP;

        // --- Overrides ---
        Overrides overrides = new Overrides();
        if (params.containsKey("angle")) {
            float angle = request.getFloat("angle");
            if (!Float.isFinite(angle)) {
                return Response.error("Parameter 'angle' must be finite");
            }
            overrides.angleRad = (float) Math.toRadians(angle);
        }
        if (params.containsKey("scale")) {
            float scale = request.getFloat("scale");
            if (!(scale > 0) || Float.isInfinite(scale)) {
                return Response.error("Parameter 'scale' must be positive, got " + scale);
            }
            overrides.scale = scale;
        }
        if (params.containsKey("xOffset")) {
            float offset = request.getFloat("xOffset");
            if (!Float.isFinite(offset)) {
                return Response.error("Parameter 'xOffset' must be a finite number of cm, got " + offset);
            }
            overrides.xOffsetCm = offset;
        }
        if (params.containsKey("yOffset")) {
            float offset = request.getFloat("yOffset");
            if (!Float.isFinite(offset)) {
                return Response.error("Parameter 'yOffset' must be a finite number of cm, got " + offset);
            }
            overrides.yOffsetCm = offset;
        }
        if (source == Source.KEEP && overrides.isEmpty()) {
            return Response.error("keepTexture=true changes nothing without at least one of "
                    + "angle, scale, xOffset, yOffset");
        }

        // --- Resolve the source description (off the EDT) ---
        CatalogTexture catalogTexture = null;
        String fromType = null;
        String fromId = null;
        String fromSurface = null;
        if (source == Source.CATALOG) {
            String textureName = request.getString("textureName");
            String textureCategory = request.getString("textureCategory");
            if (textureName != null) {
                TexturesCatalog catalog = accessor.getTexturesCatalog();
                if (catalog == null) {
                    return Response.error("Texture catalog is not available");
                }
                CatalogSearchUtil.TextureSearchResult texResult =
                        CatalogSearchUtil.findTexture(catalog, textureName, textureCategory);
                if (!texResult.isFound()) {
                    return Response.error("Texture not found: '" + textureName + "'"
                            + (textureCategory != null ? " in category '" + textureCategory + "'" : "")
                            + ". Use list_textures_catalog to browse available textures, or "
                            + "fromTargetType/fromTargetId/fromSurface to copy a texture already in the home");
                }
                catalogTexture = texResult.getFound();
            }
        } else if (source == Source.COPY) {
            fromType = request.getString("fromTargetType");
            fromId = request.getString("fromTargetId");
            fromSurface = request.getString("fromSurface");
            if (fromType == null || fromId == null || fromSurface == null) {
                return Response.error("Copying a texture needs all of fromTargetType, fromTargetId and fromSurface");
            }
            if (!"wall".equals(fromType) && !"room".equals(fromType)) {
                return Response.error("Invalid fromTargetType: '" + fromType + "'. Expected 'wall' or 'room'");
            }
            String fromError = checkSurface(fromType, fromSurface, false, "fromSurface");
            if (fromError != null) {
                return Response.error(fromError);
            }
        }

        // --- Read, build and apply on the EDT, all or nothing ---
        final CatalogTexture finalCatalogTexture = catalogTexture;
        final String finalFromType = fromType;
        final String finalFromId = fromId;
        final String finalFromSurface = fromSurface;
        Object outcome = accessor.runOnEDT(() -> {
            Home home = accessor.getHome();

            Object target = "wall".equals(targetType)
                    ? ObjectResolver.findWall(home, targetId)
                    : ObjectResolver.findRoom(home, targetId);
            if (target == null) {
                return ("wall".equals(targetType) ? "Wall" : "Room") + " not found: " + targetId;
            }

            HomeTexture copied = null;
            if (source == Source.COPY) {
                Object from = "wall".equals(finalFromType)
                        ? ObjectResolver.findWall(home, finalFromId)
                        : ObjectResolver.findRoom(home, finalFromId);
                if (from == null) {
                    return ("wall".equals(finalFromType) ? "Source wall" : "Source room")
                            + " not found: " + finalFromId;
                }
                copied = surfaceTexture(from, finalFromSurface);
                if (copied == null) {
                    return "Source " + finalFromType + " " + finalFromId + " has no texture on its "
                            + finalFromSurface + " " + surfaceNoun(finalFromType);
                }
            }

            List<String> surfaces = expand(targetType, surface);
            List<HomeTexture> textures = new ArrayList<>();
            for (String s : surfaces) {
                if (source == Source.CATALOG) {
                    textures.add(buildFromCatalog(finalCatalogTexture, overrides));
                } else if (source == Source.COPY) {
                    textures.add(buildFrom(copied, overrides));
                } else {
                    HomeTexture current = surfaceTexture(target, s);
                    if (current == null) {
                        return "keepTexture: " + targetType + " " + targetId + " has no texture on its " + s
                                + " " + surfaceNoun(targetType)
                                + " to adjust; apply one with textureName or fromTargetId first";
                    }
                    textures.add(buildFrom(current, overrides));
                }
            }

            for (int i = 0; i < surfaces.size(); i++) {
                setSurfaceTexture(target, surfaces.get(i), textures.get(i));
            }
            return target instanceof Wall
                    ? buildWallResponse((Wall) target)
                    : buildRoomResponse((Room) target);
        });

        if (outcome instanceof String) {
            return Response.error((String) outcome);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) outcome;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("targetType", targetType);
        data.put("targetId", targetId);
        data.put("surface", surface);
        data.put("source", source.name().toLowerCase());
        if (source == Source.CATALOG) {
            data.put("textureName", catalogTexture != null ? catalogTexture.getName() : null);
            data.put("textureCategory", catalogTexture != null ? catalogTexture.getCategory().getName() : null);
        } else {
            data.put("textureName", appliedName(state, targetType, surface));
            data.put("textureCategory", null);
        }
        if (source == Source.COPY) {
            Map<String, Object> from = new LinkedHashMap<>();
            from.put("targetType", fromType);
            from.put("targetId", fromId);
            from.put("surface", fromSurface);
            data.put("copiedFrom", from);
        }
        data.putAll(state);
        return Response.ok(data);
    }

    // --- Texture building ---

    /** A catalog texture with the given overrides (defaults: angle 0, scale 1, offsets 0), or null to remove. */
    private static HomeTexture buildFromCatalog(CatalogTexture found, Overrides o) {
        if (found == null) {
            return null;
        }
        return build(found, new HomeTexture(found), o, 0f, 1f, 0f, 0f, false, true);
    }

    /** A copy of an existing texture (same image, size, catalog id) with the overrides applied. */
    private static HomeTexture buildFrom(HomeTexture base, Overrides o) {
        return build(base, base, o, base.getAngle(), base.getScale(), base.getXOffset(), base.getYOffset(),
                base.isFittingArea(), base.isLeftToRightOriented());
    }

    private static HomeTexture build(TextureImage image, HomeTexture sized, Overrides o,
                                     float angle, float scale, float xFraction, float yFraction,
                                     boolean fittingArea, boolean leftToRight) {
        float newAngle = o.angleRad != null ? o.angleRad : angle;
        float newScale = o.scale != null ? o.scale : scale;
        // Offsets given in cm become a fraction of the tile at the final scale; omitted ones keep the
        // stored fraction, so the pattern keeps its phase relative to the tile.
        float newX = o.xOffsetCm != null
                ? TextureUtil.toFraction(o.xOffsetCm, TextureUtil.tileWidth(sized, newScale)) : xFraction;
        float newY = o.yOffsetCm != null
                ? TextureUtil.toFraction(o.yOffsetCm, TextureUtil.tileHeight(sized, newScale)) : yFraction;
        return new HomeTexture(image, newX, newY, newAngle, newScale, fittingArea, leftToRight);
    }

    // --- Surfaces ---

    /** Error message when the surface is not valid for the type, else null. */
    private static String checkSurface(String type, String surface, boolean allowBoth, String param) {
        List<String> valid = "wall".equals(type) ? WALL_SURFACES : ROOM_SURFACES;
        if (!valid.contains(surface) || (!allowBoth && "both".equals(surface))) {
            String expected = "wall".equals(type)
                    ? (allowBoth ? "left, right, both" : "left, right")
                    : (allowBoth ? "floor, ceiling, both" : "floor, ceiling");
            return "Invalid " + param + " '" + surface + "' for " + type + ". Expected: " + expected;
        }
        return null;
    }

    private static String surfaceNoun(String type) {
        return "wall".equals(type) ? "side" : "surface";
    }

    private static List<String> expand(String type, String surface) {
        if ("both".equals(surface)) {
            return "wall".equals(type) ? Arrays.asList("left", "right") : Arrays.asList("floor", "ceiling");
        }
        return Arrays.asList(surface);
    }

    private static HomeTexture surfaceTexture(Object item, String surface) {
        if (item instanceof Wall) {
            Wall w = (Wall) item;
            return "left".equals(surface) ? w.getLeftSideTexture() : w.getRightSideTexture();
        }
        Room r = (Room) item;
        return "floor".equals(surface) ? r.getFloorTexture() : r.getCeilingTexture();
    }

    private static void setSurfaceTexture(Object item, String surface, HomeTexture texture) {
        if (item instanceof Wall) {
            Wall w = (Wall) item;
            if ("left".equals(surface)) {
                w.setLeftSideTexture(texture);
            } else {
                w.setRightSideTexture(texture);
            }
        } else {
            Room r = (Room) item;
            if ("floor".equals(surface)) {
                r.setFloorTexture(texture);
            } else {
                r.setCeilingTexture(texture);
            }
        }
    }

    // --- Response builders ---

    private static Map<String, Object> buildWallResponse(Wall wall) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("leftSideTexture", textureName(wall.getLeftSideTexture()));
        result.put("rightSideTexture", textureName(wall.getRightSideTexture()));
        result.put("leftSideTextureInfo", TextureUtil.textureInfo(wall.getLeftSideTexture()));
        result.put("rightSideTextureInfo", TextureUtil.textureInfo(wall.getRightSideTexture()));
        return result;
    }

    private static Map<String, Object> buildRoomResponse(Room room) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("floorTexture", textureName(room.getFloorTexture()));
        result.put("ceilingTexture", textureName(room.getCeilingTexture()));
        result.put("floorTextureInfo", TextureUtil.textureInfo(room.getFloorTexture()));
        result.put("ceilingTextureInfo", TextureUtil.textureInfo(room.getCeilingTexture()));
        return result;
    }

    /** Name of the texture now on the target surface(s); null when "both" ended up with different names. */
    private static Object appliedName(Map<String, Object> state, String type, String surface) {
        List<String> surfaces = expand(type, surface);
        Object first = state.get(nameKey(type, surfaces.get(0)));
        for (String s : surfaces) {
            Object name = state.get(nameKey(type, s));
            if (name == null ? first != null : !name.equals(first)) {
                return null;
            }
        }
        return first;
    }

    private static String nameKey(String type, String surface) {
        if ("wall".equals(type)) {
            return "left".equals(surface) ? "leftSideTexture" : "rightSideTexture";
        }
        return "floor".equals(surface) ? "floorTexture" : "ceilingTexture";
    }

    // --- Descriptor ---

    @Override
    public String getDescription() {
        return "Puts a texture on a wall side or room surface, or adjusts the one already there. "
                + "Walls: surface 'left', 'right' or 'both' (sides relative to the wall direction from start to end). "
                + "Rooms: 'floor', 'ceiling' or 'both'. "
                + "Texture source, exactly one of: textureName (from list_textures_catalog; null removes the texture), "
                + "fromTargetType/fromTargetId/fromSurface (copy the texture already on another wall side or room "
                + "surface, e.g. one imported from a photo that is not in the catalog: same image, size, angle, scale, "
                + "offsets and fittingArea), or keepTexture=true (keep the texture on each target surface and change "
                + "only the angle/scale/xOffset/yOffset given). "
                + "angle (degrees), scale, xOffset and yOffset (cm) override the source's values; a catalog texture "
                + "defaults to 0, 1, 0, 0; omitted values are kept when copying or keeping. "
                + "Offsets move the pattern without changing the image. Wall side: +yOffset moves it UP (vertical "
                + "origin: elevation 0 of the home, so patterns line up across walls and levels); +xOffset moves it "
                + "to the RIGHT as seen facing that side (horizontal origin: the wall's start point; mirrored on the "
                + "left side for textures with leftToRightOriented=false, found only in old homes). "
                + "Floor: +xOffset towards plan +x, +yOffset towards plan -y (up the plan). Ceiling: +xOffset towards "
                + "plan +x, +yOffset towards plan +y. One tile (texture width or height x scale) is a full repeat. "
                + "Offsets do nothing on a room texture with fittingArea. get_state/query_state report each surface's "
                + "texture as <surface>TextureInfo (name, size, angle, scale, xOffset and yOffset in cm). "
                + "A texture overrides any solid color on that surface; to go back to a color, remove the texture "
                + "with textureName=null, then use modify_wall/modify_room. Undo with checkpoint / restore_checkpoint.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .requiredEnum("targetType", "Type of object to apply texture to",
                        "wall", "room")
                .requiredString("targetId", "Object ID from get_state")
                .requiredString("surface",
                        "Surface to apply texture to. Walls: 'left', 'right', 'both'. Rooms: 'floor', 'ceiling', 'both'")
                .raw("textureName", nullableProp("string",
                        "Texture source 1: exact texture name from list_textures_catalog, or null to remove texture"))
                .string("textureCategory",
                        "Category name to disambiguate if multiple textures share the same name")
                .enumProp("fromTargetType",
                        "Texture source 2: copy the texture on another object's surface. Type of that object",
                        "wall", "room")
                .string("fromTargetId", "ID (from get_state) of the object whose texture is copied")
                .string("fromSurface", "Surface whose texture is copied: 'left'/'right' for a wall, "
                        + "'floor'/'ceiling' for a room")
                .bool("keepTexture", "Texture source 3: true keeps the texture already on each target surface "
                        + "and changes only the angle/scale/xOffset/yOffset given (at least one is required)")
                .number("angle", "Texture rotation angle in degrees (catalog default 0)")
                .number("scale", "Texture scale factor (catalog default 1.0, values > 1 enlarge the pattern)")
                .number("xOffset", "Horizontal pattern shift in cm (catalog default 0). Wall side: + moves the "
                        + "pattern right as seen facing the side. Floor and ceiling: + moves it towards plan +x")
                .number("yOffset", "Vertical pattern shift in cm (catalog default 0). Wall side: + moves the "
                        + "pattern up. Floor: + moves it towards plan -y. Ceiling: + moves it towards plan +y")
                .build();
    }

}
