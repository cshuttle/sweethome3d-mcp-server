package com.sh3d.mcp.command.util;

import com.eteks.sweethome3d.model.HomeTexture;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.sh3d.mcp.command.util.FormatUtil.round2;

/**
 * Texture offsets in centimetres, and the texture details that get_state reports.
 *
 * <p>Sweet Home 3D 7.5 stores {@link HomeTexture#getXOffset()} and {@link HomeTexture#getYOffset()} as a
 * fraction of one tile: {@code Object3DBranch.getTextureAttributes} translates the surface's texture
 * coordinates (in cm) by {@code -xOffset * scale * width} and {@code -yOffset * scale * height} before
 * rotating and scaling them, so an offset of 1 shifts the pattern by exactly one tile. The MCP API speaks
 * centimetres; these helpers convert. A texture whose width or height is unknown (-1) is drawn as a
 * 100 cm tile, as Sweet Home 3D does.
 *
 * <p>Because the translation is applied before the rotation, an offset in cm moves the pattern along
 * the surface's own axes whatever the texture angle. The offsets have no effect on a room texture
 * with {@code fittingArea} set: Sweet Home 3D stretches that one over the room's bounding box.
 */
public final class TextureUtil {

    /** Tile size Sweet Home 3D uses for a texture of unknown size. */
    private static final float UNKNOWN_SIZE_TILE = 100f;

    private TextureUtil() {
    }

    /** Width in cm of one tile of the texture at the given scale. */
    public static float tileWidth(HomeTexture texture, float scale) {
        return baseSize(texture, true) * scale;
    }

    /** Height in cm of one tile of the texture at the given scale. */
    public static float tileHeight(HomeTexture texture, float scale) {
        return baseSize(texture, false) * scale;
    }

    /** The texture's horizontal offset in cm. */
    public static float xOffsetCm(HomeTexture texture) {
        return texture.getXOffset() * tileWidth(texture, texture.getScale());
    }

    /** The texture's vertical offset in cm. */
    public static float yOffsetCm(HomeTexture texture) {
        return texture.getYOffset() * tileHeight(texture, texture.getScale());
    }

    /** Converts an offset in cm to the fraction of a tile Sweet Home 3D stores. */
    public static float toFraction(float offsetCm, float tileSize) {
        return offsetCm / tileSize;
    }

    /**
     * Details of a texture as get_state and apply_texture report them, or null without a texture:
     * name, catalogId, width and height (cm, one tile at scale 1), angle (degrees), scale,
     * xOffset and yOffset (cm), fittingArea and leftToRightOriented.
     */
    public static Map<String, Object> textureInfo(HomeTexture texture) {
        if (texture == null) {
            return null;
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("name", texture.getName());
        info.put("catalogId", texture.getCatalogId());
        info.put("width", round2(texture.getWidth()));
        info.put("height", round2(texture.getHeight()));
        info.put("angle", round2(Math.toDegrees(texture.getAngle())));
        info.put("scale", round2(texture.getScale()));
        info.put("xOffset", round2(xOffsetCm(texture)));
        info.put("yOffset", round2(yOffsetCm(texture)));
        info.put("fittingArea", texture.isFittingArea());
        info.put("leftToRightOriented", texture.isLeftToRightOriented());
        return info;
    }

    private static float baseSize(HomeTexture texture, boolean width) {
        if (texture.getWidth() == -1 || texture.getHeight() == -1) {
            return UNKNOWN_SIZE_TILE;
        }
        return width ? texture.getWidth() : texture.getHeight();
    }
}
