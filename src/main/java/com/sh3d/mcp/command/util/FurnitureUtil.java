package com.sh3d.mcp.command.util;

import com.eteks.sweethome3d.model.HomeFurnitureGroup;
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
     *
     * <p>A group keeps its own field, computed from its pieces ({@code HomeFurnitureGroup.updateLocationAndSize}:
     * the highest drop height of the pieces that are drop targets, negative when none is) and never saved, so a
     * group is switched off by switching off every piece in it, nested groups included, and its own field is set
     * to match until the home is next loaded. Only a negative value is accepted for a group.
     */
    public static void setDropOnTopElevation(HomePieceOfFurniture piece, float value) {
        if (piece instanceof HomeFurnitureGroup) {
            if (value >= 0) {
                throw new IllegalArgumentException("a group's dropOnTopElevation comes from its pieces");
            }
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                setDropOnTopElevation(child, value);
            }
            set(HomeFurnitureGroup.class, piece, value);
        } else {
            set(HomePieceOfFurniture.class, piece, value);
        }
    }

    private static void set(Class<?> owner, HomePieceOfFurniture piece, float value) {
        try {
            Field field = owner.getDeclaredField("dropOnTopElevation");
            field.setAccessible(true);
            field.setFloat(piece, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot set dropOnTopElevation on this Sweet Home 3D version", e);
        }
    }
}
