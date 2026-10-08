package com.sh3d.mcp.command.util;

import com.eteks.sweethome3d.model.CatalogTexture;
import com.eteks.sweethome3d.model.HomeTexture;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TextureUtilTest {

    private static HomeTexture texture(float width, float height, float xOffset, float yOffset, float scale) {
        return new HomeTexture(new CatalogTexture("Stone", null, width, height),
                xOffset, yOffset, 0f, scale, false, true);
    }

    @Test
    void offsetsAreAFractionOfTheScaledTile() {
        HomeTexture t = texture(60f, 40f, 0.25f, -0.5f, 2f);

        assertEquals(120f, TextureUtil.tileWidth(t, t.getScale()), 1e-6f);
        assertEquals(80f, TextureUtil.tileHeight(t, t.getScale()), 1e-6f);
        assertEquals(30f, TextureUtil.xOffsetCm(t), 1e-6f);
        assertEquals(-40f, TextureUtil.yOffsetCm(t), 1e-6f);
    }

    @Test
    void toFractionInvertsTheConversion() {
        HomeTexture t = texture(60f, 40f, 0f, 0f, 1.5f);

        float fraction = TextureUtil.toFraction(20f, TextureUtil.tileHeight(t, 1.5f));

        assertEquals(20f / 60f, fraction, 1e-6f);
        assertEquals(20f, TextureUtil.yOffsetCm(texture(60f, 40f, 0f, fraction, 1.5f)), 1e-4f);
    }

    @Test
    void unknownSizeIsAHundredCentimetreTile() {
        HomeTexture t = texture(-1f, 40f, 0.5f, 0.5f, 1f);

        assertEquals(100f, TextureUtil.tileWidth(t, 1f), 0f);
        assertEquals(100f, TextureUtil.tileHeight(t, 1f), 0f);
        assertEquals(50f, TextureUtil.yOffsetCm(t), 0f);
    }

    @Test
    void textureInfoReportsOffsetsInCentimetres() {
        HomeTexture t = new HomeTexture(new CatalogTexture("Stone", null, 60f, 40f),
                0.1f, 0.5f, (float) Math.toRadians(45), 1.5f, true, false);

        Map<String, Object> info = TextureUtil.textureInfo(t);

        assertEquals("Stone", info.get("name"));
        assertEquals(60.0, info.get("width"));
        assertEquals(40.0, info.get("height"));
        assertEquals(45.0, info.get("angle"));
        assertEquals(1.5, info.get("scale"));
        assertEquals(9.0, info.get("xOffset"));
        assertEquals(30.0, info.get("yOffset"));
        assertEquals(true, info.get("fittingArea"));
        assertEquals(false, info.get("leftToRightOriented"));
    }

    @Test
    void textureInfoOfNoTextureIsNull() {
        assertNull(TextureUtil.textureInfo(null));
    }
}
