package com.sh3d.mcp.command.util;

import com.eteks.sweethome3d.model.HomePieceOfFurniture;

import java.lang.reflect.Field;

/**
 * Furniture properties that Sweet Home 3D 7.5 stores and saves but gives no public setter.
 */
public final class FurnitureUtil {

    private FurnitureUtil() {
    }

    /**
     * Sets the piece's drop-on-top elevation: where a piece dropped on it lands, as a fraction of its
     * height (1 by default, its top). A negative value makes the plan ignore the piece as a drop target
     * ({@code FurnitureController.getSurroundingFurniture}). The value is saved in Home.xml
     * ({@code HomeXMLExporter} writes {@code dropOnTopElevation}) and in the serialized home.
     */
    public static void setDropOnTopElevation(HomePieceOfFurniture piece, float value) {
        try {
            Field field = HomePieceOfFurniture.class.getDeclaredField("dropOnTopElevation");
            field.setAccessible(true);
            field.setFloat(piece, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set dropOnTopElevation on this Sweet Home 3D version", e);
        }
    }
}
