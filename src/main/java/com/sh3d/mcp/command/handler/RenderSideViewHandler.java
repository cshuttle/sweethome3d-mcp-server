package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;

import com.eteks.sweethome3d.j3d.Component3DManager;
import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Level;
import com.eteks.sweethome3d.swing.HomeComponent3D;
import com.eteks.sweethome3d.viewcontroller.Object3DFactory;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.bridge.PathValidator;
import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import javax.imageio.ImageIO;
import javax.media.j3d.BoundingBox;
import javax.vecmath.Point3d;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Handler for "render_side_view": a flat (parallel projection) elevation of the home, drawn by
 * Sweet Home 3D's own 3D view in its SIDE_VIEW projection, the mode the SideView plugin shows.
 *
 * <p>The live home is never touched: a clone is rendered, with its top camera turned to the
 * requested yaw, and with only the requested levels viewable and visible.
 *
 * <p>Elevation or section. The SIDE_VIEW view sits at the camera's plan position and clips
 * everything closer than about a centimetre, so where the camera stands decides what is drawn:
 * outside the home's bounds gives the exterior elevation; on a plane through the home gives a
 * section cut by that plane, looking away from the viewer. Either way the camera stays on the line
 * through the bounds' centre along the view, so the image stays centred on the home.
 *
 * <p>Scale. In SIDE_VIEW, HomeComponent3D scales the view platform by
 * {@code 0.48 * hypot(dx, dy)} of its approximate home bounds (plan extent of the viewable items),
 * centred laterally on the camera and vertically on the bounds' mid-height
 * ({@code updateViewPlatformTransform}). The off-screen canvas is 2 m of physical screen across
 * ({@code Component3DManager.getOffScreenCanvas3D}), so the image spans {@code 2 * scale} cm and
 * one cm is {@code width / (2 * scale)} pixels, the same along both axes.
 */
public class RenderSideViewHandler implements CommandHandler, CommandDescriptor {

    private static final Logger LOG = Logger.getLogger(RenderSideViewHandler.class.getName());

    static final int MAX_DIMENSION = 4096;
    static final int MIN_DIMENSION = 16;
    static final int DEFAULT_WIDTH = 1600;
    static final int DEFAULT_HEIGHT = 1000;
    /** HomeComponent3D.updateViewPlatformTransform's SIDE_VIEW scale factor (Sweet Home 3D 7.5). */
    static final float SIDE_VIEW_SCALE_FACTOR = 0.48f;
    /** How far outside the bounds (cm) the camera stands for an elevation. */
    static final double ELEVATION_STANDOFF = 100;

    /** Plan edge the viewer stands at, and the camera yaw (degrees) looking across the plan from it. */
    static final Map<String, Float> SIDE_YAWS = new LinkedHashMap<>();
    static {
        SIDE_YAWS.put("top", 0f);      // stands at plan -y, looks +y
        SIDE_YAWS.put("right", 90f);   // stands at plan +x, looks -x
        SIDE_YAWS.put("bottom", 180f); // stands at plan +y, looks -y
        SIDE_YAWS.put("left", 270f);   // stands at plan -x, looks +x
    }

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        final int width = (int) request.getFloat("width", DEFAULT_WIDTH);
        final int height = (int) request.getFloat("height", DEFAULT_HEIGHT);
        if (width < MIN_DIMENSION || width > MAX_DIMENSION) {
            return Response.error("Parameter 'width' must be between " + MIN_DIMENSION + " and "
                    + MAX_DIMENSION + ", got " + width);
        }
        if (height < MIN_DIMENSION || height > MAX_DIMENSION) {
            return Response.error("Parameter 'height' must be between " + MIN_DIMENSION + " and "
                    + MAX_DIMENSION + ", got " + height);
        }

        final float yawDegrees;
        final String side = request.getString("side");
        try {
            yawDegrees = resolveYawDegrees(side, request.getParams().get("yaw"));
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }

        final List<String> levelRefs;
        try {
            levelRefs = parseLevelRefs(request.getParams().get("levels"));
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }

        final double[] sectionAt;
        try {
            sectionAt = parseSectionAt(request.getParams().get("sectionAt"));
        } catch (IllegalArgumentException e) {
            return Response.error(e.getMessage());
        }

        Path path = null;
        String filePath = request.getString("filePath");
        if (filePath != null && !filePath.trim().isEmpty()) {
            try {
                path = PathValidator.validateAndNormalize(filePath, ".png");
            } catch (IllegalArgumentException | IOException e) {
                return Response.error(e.getMessage());
            }
        }

        try {
            if (!Component3DManager.getInstance().isOffScreenImageSupported()) {
                return Response.error("Off-screen 3D rendering is not supported by this Java 3D setup");
            }
            final Map<String, Object> view = new LinkedHashMap<>();
            // Only the clone is taken on the EDT. The render runs on this thread: right after the app
            // starts, its 3D view is still loading models, and a render that waits for them on the EDT
            // blocks the EDT that their loading needs (the app froze). The clone is ours alone.
            final Home clone = accessor.runOnEDT(() -> accessor.getHome().clone());
            List<String> shown = applyLevels(clone, levelRefs);
            clone.getEnvironment().setAllLevelsVisible(true);

            HomeComponent3D component = new HomeComponent3D(clone, accessor.getUserPreferences(),
                    (Object3DFactory) null, HomeComponent3D.Projection.SIDE_VIEW, null);
            BoundingBox bounds = approximateHomeBounds(component);
            if (bounds == null) {
                throw new IllegalStateException("Nothing viewable to draw on the requested levels");
            }
            Point3d lower = new Point3d();
            Point3d upper = new Point3d();
            bounds.getLower(lower);
            bounds.getUpper(upper);
            float centerX = (float) ((lower.x + upper.x) / 2);
            float centerY = (float) ((lower.y + upper.y) / 2);
            float centerZ = (float) ((lower.z + upper.z) / 2);

            double[] cameraXY = cameraPosition(yawDegrees, centerX, centerY,
                    upper.x - lower.x, upper.y - lower.y, sectionAt);
            Camera camera = clone.getTopCamera();
            camera.setX((float) cameraXY[0]);
            camera.setY((float) cameraXY[1]);
            camera.setYaw((float) Math.toRadians(yawDegrees));
            clone.setCamera(camera);

            double pxPerCm = pixelsPerCm(width, upper.x - lower.x, upper.y - lower.y);
            double[] right = rightVector(yawDegrees);
            view.put("yaw", yawDegrees);
            if (side != null) {
                view.put("side", side);
            }
            view.put("levels", shown);
            view.put("mode", sectionAt == null ? "elevation" : "section");
            if (sectionAt != null) {
                Map<String, Object> cut = new LinkedHashMap<>();
                cut.put("x", round(cameraXY[0], 2));
                cut.put("y", round(cameraXY[1], 2));
                view.put("sectionAt", cut);
            }
            view.put("pxPerCm", round(pxPerCm, 5));
            Map<String, Object> center = new LinkedHashMap<>();
            center.put("x", round(centerX, 2));
            center.put("y", round(centerY, 2));
            center.put("z", round(centerZ, 2));
            view.put("center", center);
            Map<String, Object> rightMap = new LinkedHashMap<>();
            rightMap.put("x", round(right[0], 6));
            rightMap.put("y", round(right[1], 6));
            view.put("right", rightMap);
            Map<String, Object> boundsMap = new LinkedHashMap<>();
            boundsMap.put("xMin", round(lower.x, 2));
            boundsMap.put("yMin", round(lower.y, 2));
            boundsMap.put("zMin", round(lower.z, 2));
            boundsMap.put("xMax", round(upper.x, 2));
            boundsMap.put("yMax", round(upper.y, 2));
            boundsMap.put("zMax", round(upper.z, 2));
            view.put("bounds", boundsMap);
            view.put("planToPixel", "u = width/2 + ((x - center.x) * right.x + (y - center.y) * right.y) * pxPerCm;"
                    + " v = height/2 - (z - center.z) * pxPerCm (x, y plan cm; z absolute elevation cm)");
            BufferedImage image = component.getOffScreenImage(width, height);

            Map<String, Object> data = new LinkedHashMap<>();
            if (path != null) {
                ImageIO.write(image, "png", path.toFile());
                data.put("filePath", path.toString());
                data.put("width", image.getWidth());
                data.put("height", image.getHeight());
                data.put("size_bytes", (int) Files.size(path));
            } else {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                data.put("_image", Base64.getEncoder().encodeToString(baos.toByteArray()));
                data.put("_mimeType", "image/png");
                data.put("width", image.getWidth());
                data.put("height", image.getHeight());
                data.put("size_bytes", baos.size());
            }
            data.putAll(view);
            LOG.info("Rendered side view yaw=" + yawDegrees + " " + width + "x" + height
                    + (path != null ? " to " + path : ""));
            return Response.ok(data);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Response.error(e.getMessage());
        } catch (Exception e) {
            LOG.log(java.util.logging.Level.WARNING, "Side view render failed", e);
            return Response.error("Side view render failed: " + e.getMessage());
        }
    }

    /** Yaw in degrees from either {@code side} or {@code yaw}; one of them at most, default bottom. */
    static float resolveYawDegrees(String side, Object yaw) {
        if (side != null && yaw != null) {
            throw new IllegalArgumentException("Give either 'side' or 'yaw', not both");
        }
        if (yaw != null) {
            if (!(yaw instanceof Number)) {
                throw new IllegalArgumentException("Parameter 'yaw' must be a number of degrees");
            }
            double value = ((Number) yaw).doubleValue();
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("Parameter 'yaw' must be finite");
            }
            return (float) (((value % 360) + 360) % 360);
        }
        String key = side == null ? "bottom" : side.trim().toLowerCase();
        Float value = SIDE_YAWS.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Unknown side '" + side + "'; expected one of " + SIDE_YAWS.keySet());
        }
        return value;
    }

    /** Plan direction (unit vector) the camera looks along at this yaw: yaw 0 looks +y, yaw 90 looks -x. */
    static double[] lookVector(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new double[] {-Math.sin(yaw), Math.cos(yaw)};
    }

    /**
     * Camera plan position: on the line through the bounds' centre along the view, either outside the
     * bounds facing them (elevation) or where the section plane through {@code sectionAt} crosses it.
     */
    static double[] cameraPosition(float yawDegrees, double centerX, double centerY,
                                   double dx, double dy, double[] sectionAt) {
        double[] look = lookVector(yawDegrees);
        double distance;
        if (sectionAt == null) {
            distance = -(Math.sqrt(dx * dx + dy * dy) / 2 + ELEVATION_STANDOFF);
        } else {
            distance = (sectionAt[0] - centerX) * look[0] + (sectionAt[1] - centerY) * look[1];
        }
        return new double[] {centerX + look[0] * distance, centerY + look[1] * distance};
    }

    static double[] parseSectionAt(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List) || ((List<?>) raw).size() != 2) {
            throw new IllegalArgumentException("Parameter 'sectionAt' must be a plan point [x, y] in cm");
        }
        double[] point = new double[2];
        for (int i = 0; i < 2; i++) {
            Object value = ((List<?>) raw).get(i);
            if (!(value instanceof Number) || !Double.isFinite(((Number) value).doubleValue())) {
                throw new IllegalArgumentException("Parameter 'sectionAt' must hold two finite numbers");
            }
            point[i] = ((Number) value).doubleValue();
        }
        return point;
    }

    /**
     * Plan direction (unit vector) that points to the image's right for a camera at this yaw.
     * Yaw 0 looks +y, so its right is -x; yaw 90 looks -x, so its right is -y.
     */
    static double[] rightVector(float yawDegrees) {
        double yaw = Math.toRadians(yawDegrees);
        return new double[] {-Math.cos(yaw), -Math.sin(yaw)};
    }

    /** Pixels per cm of a SIDE_VIEW image {@code width} pixels wide, for bounds of this plan extent. */
    static double pixelsPerCm(int width, double dx, double dy) {
        double scale = Math.sqrt(dx * dx + dy * dy) * SIDE_VIEW_SCALE_FACTOR;
        return width / (2 * scale);
    }

    static List<String> parseLevelRefs(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof List)) {
            throw new IllegalArgumentException("Parameter 'levels' must be an array of level ids or names");
        }
        List<String> refs = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (!(item instanceof String) || ((String) item).trim().isEmpty()) {
                throw new IllegalArgumentException("Parameter 'levels' must hold non-empty level ids or names");
            }
            refs.add(((String) item).trim());
        }
        if (refs.isEmpty()) {
            throw new IllegalArgumentException("Parameter 'levels' must name at least one level");
        }
        return refs;
    }

    /** Makes only the referenced levels viewable in {@code clone}; returns the names of the levels shown. */
    private static List<String> applyLevels(Home clone, List<String> refs) {
        List<String> shown = new ArrayList<>();
        // A level's 'visible' flag follows the selected level when the home does not show all levels
        // (LevelController); the clone draws every level it keeps.
        if (refs == null) {
            for (Level level : clone.getLevels()) {
                if (level.isViewable()) {
                    level.setVisible(true);
                    shown.add(level.getName());
                }
            }
            return shown;
        }
        List<Level> keep = new ArrayList<>();
        for (String ref : refs) {
            List<Level> matches = ImportFurnitureHandler.findLevels(clone, ref);
            if (matches.isEmpty()) {
                throw new IllegalArgumentException("Level not found: " + ref);
            }
            if (matches.size() > 1) {
                throw new IllegalArgumentException("Several levels are named '" + ref + "'; pass the level id instead");
            }
            keep.add(matches.get(0));
        }
        for (Level level : clone.getLevels()) {
            boolean viewable = keep.contains(level);
            level.setViewable(viewable);
            if (viewable) {
                level.setVisible(true);
                shown.add(level.getName());
            }
        }
        return shown;
    }

    /** HomeComponent3D's own bounds, the ones its SIDE_VIEW scale and centre come from (private in 7.5). */
    private static BoundingBox approximateHomeBounds(HomeComponent3D component) throws Exception {
        Method method = HomeComponent3D.class.getDeclaredMethod("getApproximateHomeBounds");
        method.setAccessible(true);
        return (BoundingBox) method.invoke(component);
    }

    private static double round(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }

    @Override
    public String getDescription() {
        return "Renders a flat side elevation (parallel projection, no perspective) of the home as Sweet Home 3D "
                + "draws it: its own 3D view in side-view mode, so faces the app culls and its ambient lighting "
                + "show as they do in the app. The live home and its camera are untouched (a clone is rendered). "
                + "Choose the direction with 'side' (the plan edge the viewer stands at: top, right, bottom, left; "
                + "default bottom) or 'yaw' in degrees (set_camera convention: 0 looks +y, 90 looks -x). "
                + "By default it is the exterior elevation; 'sectionAt' [x, y] instead cuts the home with the "
                + "vertical plane through that plan point, square to the view, and draws what lies beyond it. "
                + "'levels' limits the drawing to some levels (ids or names), e.g. to leave out a terrain level. "
                + "The view is fitted to the viewable items' plan extent, so the scale changes with 'levels'. "
                + "Returns pxPerCm, center, right and a planToPixel formula mapping plan x/y and absolute z (cm) "
                + "to image pixels. PNG inline (base64), or saved to 'filePath' with metadata only.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .enumProp("side", "Plan edge the viewer stands at, looking across the plan (default bottom)",
                        new ArrayList<>(SIDE_YAWS.keySet()))
                .number("yaw", "Camera yaw in degrees instead of 'side' (0 looks +y, 90 -x, 180 -y, 270 +x)")
                .array("sectionAt", SchemaBuilder.arrayDef(
                                "Plan point [x, y] in cm: draw a section cut by the vertical plane through it, "
                                + "square to the view (default: exterior elevation)")
                        .itemsOfType("number").minItems(2).maxItems(2).build())
                .array("levels", SchemaBuilder.arrayDef(
                                "Level ids or names to draw (default: the levels viewable in the home)")
                        .itemsOfType("string").build())
                .integerWithDefault("width", "Image width in pixels (" + MIN_DIMENSION + "-" + MAX_DIMENSION + ")",
                        DEFAULT_WIDTH)
                .integerWithDefault("height", "Image height in pixels (" + MIN_DIMENSION + "-" + MAX_DIMENSION + ")",
                        DEFAULT_HEIGHT)
                .string("filePath", "Absolute path to save the PNG (Windows form when the app runs on Windows). "
                        + "If given, returns metadata only")
                .build();
    }
}
