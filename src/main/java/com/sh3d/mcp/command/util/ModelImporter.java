package com.sh3d.mcp.command.util;

import com.eteks.sweethome3d.j3d.ModelManager;
import com.eteks.sweethome3d.j3d.OBJWriter;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.PieceOfFurniture;
import com.eteks.sweethome3d.tools.OperatingSystem;
import com.eteks.sweethome3d.tools.TemporaryURLContent;
import com.eteks.sweethome3d.tools.URLContent;
import com.sh3d.mcp.bridge.PathValidator;
import com.sh3d.mcp.protocol.JsonUtil;

import javax.media.j3d.BranchGroup;
import javax.media.j3d.Transform3D;
import javax.vecmath.Matrix3f;
import javax.vecmath.Vector3f;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies a 3D model file into home content, the way Sweet Home 3D's Import furniture
 * wizard does ({@code ImportedFurnitureWizardStepsPanel.copyToTemporaryOBJContent}):
 * the model is loaded with {@link ModelManager}, then written with {@link OBJWriter}
 * into a fresh temporary ZIP holding the OBJ, its MTL and its texture images. The home
 * then saves that ZIP inside the .sh3d, so the piece no longer needs the source file.
 *
 * <p>Every call writes a new temporary file, so every import gets a content URL never
 * used before: Sweet Home 3D caches models by content URL, and a reused URL can render
 * an earlier shape.
 */
public final class ModelImporter {

    /** Model rotation for Y-up models (OBJ convention, the wizard's default). */
    public static final float[][] Y_UP = PieceOfFurniture.IDENTITY_ROTATION;

    /** Model rotation for Z-up models: -90 degrees around X, so +Z becomes up. */
    public static final float[][] Z_UP = {{1, 0, 0}, {0, 0, 1}, {0, -1, 0}};

    private static final String[] MODEL_EXTENSIONS = {".obj", ".dae", ".3ds", ".lws"};

    private ModelImporter() {
    }

    /** A model copied into home content, with its size as the wizard measures it. */
    public static final class ImportedModel {
        public final Content content;
        public final float width;
        public final float depth;
        public final float height;
        public final Long modelSize;
        public final int modelFlags;

        ImportedModel(Content content, Vector3f size, Long modelSize, int modelFlags) {
            this.content = content;
            this.width = size.x;
            this.depth = size.z;
            this.height = size.y;
            this.modelSize = modelSize;
            this.modelFlags = modelFlags;
        }
    }

    /**
     * Parses the modelRotation parameter: null or "yUp" (identity), "zUp", or a 3x3
     * matrix given as three rows of three numbers.
     *
     * @throws IllegalArgumentException when the value is none of these
     */
    public static float[][] parseRotation(Object raw) {
        if (raw == null || "yUp".equals(raw)) {
            return Y_UP;
        }
        if ("zUp".equals(raw)) {
            return Z_UP;
        }
        if (raw instanceof String && ((String) raw).trim().startsWith("[")) {
            raw = JsonUtil.parse((String) raw);  // some clients send arrays as JSON text
        }
        String error = "modelRotation must be 'yUp', 'zUp' or a 3x3 matrix [[m00,m01,m02],[m10,m11,m12],[m20,m21,m22]]";
        if (!(raw instanceof List) || ((List<?>) raw).size() != 3) {
            throw new IllegalArgumentException(error);
        }
        float[][] rotation = new float[3][3];
        for (int i = 0; i < 3; i++) {
            Object row = ((List<?>) raw).get(i);
            if (!(row instanceof List) || ((List<?>) row).size() != 3) {
                throw new IllegalArgumentException(error);
            }
            for (int j = 0; j < 3; j++) {
                Object value = ((List<?>) row).get(j);
                if (!(value instanceof Number)) {
                    throw new IllegalArgumentException(error);
                }
                rotation[i][j] = ((Number) value).floatValue();
            }
        }
        return rotation;
    }

    /**
     * Fits a size to a model's natural size: given dimensions are kept, missing ones
     * follow the scale of the first given one (width, then depth, then height), and
     * none given means the natural size.
     *
     * @return {width, depth, height}
     */
    public static float[] fitSize(ImportedModel model, Float width, Float depth, Float height) {
        float scale = width != null ? width / model.width
                : depth != null ? depth / model.depth
                : height != null ? height / model.height
                : 1f;
        return new float[] {
                width != null ? width : model.width * scale,
                depth != null ? depth : model.depth * scale,
                height != null ? height : model.height * scale
        };
    }

    /**
     * Loads a model file (OBJ with its MTL and textures beside it, DAE, 3DS, LWS, or a
     * ZIP holding one of these) and copies it into a new temporary OBJ ZIP.
     *
     * @param rotation the model rotation, used to measure the natural size
     * @throws IOException when the file is missing or no loader can read it
     */
    public static ImportedModel importModel(String filePath, float[][] rotation) throws IOException {
        File file = PathValidator.normalizeOnly(filePath).toFile();
        if (!file.isFile()) {
            throw new IOException("File not found: " + file);
        }
        if (!file.canRead()) {
            throw new IOException("File is not readable: " + file);
        }

        ModelManager manager = ModelManager.getInstance();
        BranchGroup model = file.getName().toLowerCase().endsWith(".zip")
                ? loadFromZip(file)
                : load(new URLContent(file.toURI().toURL()), file.getName());

        Transform3D transform = new Transform3D();
        transform.setRotation(new Matrix3f(
                rotation[0][0], rotation[0][1], rotation[0][2],
                rotation[1][0], rotation[1][1], rotation[1][2],
                rotation[2][0], rotation[2][1], rotation[2][2]));
        Vector3f size = manager.getSize(model, transform);

        // Same flag the wizard sets by default: hide edge_color materials when present
        int flags = 0;
        for (HomeMaterial material : manager.getMaterials(model)) {
            if (material.getName() != null && material.getName().startsWith("edge_color")) {
                flags = PieceOfFurniture.HIDE_EDGE_COLOR_MATERIAL;
                break;
            }
        }

        URLContent content = copyToTemporaryOBJContent(model, file.getName());
        return new ImportedModel(content, size, content.getSize(), flags);
    }

    private static BranchGroup load(URLContent content, String name) throws IOException {
        try {
            return ModelManager.getInstance().loadModel(content);
        } catch (IOException | RuntimeException e) {
            throw new IOException("Cannot read " + name + " as a 3D model (Sweet Home 3D reads OBJ, DAE, 3DS, "
                    + "LWS, or a ZIP holding one of these): " + e.getMessage(), e);
        }
    }

    /** Copies the ZIP to a temporary file and loads its first model entry, as the wizard does. */
    private static BranchGroup loadFromZip(File file) throws IOException {
        URLContent copy = TemporaryURLContent.copyToTemporaryURLContent(new URLContent(file.toURI().toURL()));
        IOException lastError = null;
        try (ZipFile zip = new ZipFile(new File(URI.create(copy.getURL().toString())))) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String entry = entries.nextElement().getName();
                if (!isModelFile(entry)) {
                    continue;
                }
                URL url = new URL("jar:" + copy.getURL() + "!/"
                        + URLEncoder.encode(entry, "UTF-8").replace("+", "%20").replace("%2F", "/"));
                try {
                    return load(new TemporaryURLContent(url), entry);
                } catch (IOException e) {
                    lastError = e;
                }
            }
        }
        throw lastError != null ? lastError
                : new IOException("No OBJ, DAE, 3DS or LWS model found in " + file.getName());
    }

    private static boolean isModelFile(String entry) {
        String lower = entry.toLowerCase();
        if (lower.endsWith("/") || lower.substring(lower.lastIndexOf('/') + 1).startsWith(".")) {
            return false;
        }
        for (String extension : MODEL_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /** Same naming and writing as {@code ImportedFurnitureWizardStepsPanel.copyToTemporaryOBJContent}. */
    private static URLContent copyToTemporaryOBJContent(BranchGroup model, String fileName) throws IOException {
        String objName = fileName.toLowerCase().endsWith(".obj") ? fileName : fileName + ".obj";
        if (objName.matches(".*[^a-zA-Z0-9_\\.\\-\\ ].*")) {
            objName = "model.obj";
        }
        File zip = OperatingSystem.createTemporaryFile("import", ".zip");
        OBJWriter.writeNodeInZIPFile(model, zip, 0, objName, "3D model import " + fileName);
        return new TemporaryURLContent(new URL("jar:" + zip.toURI().toURL() + "!/"
                + URLEncoder.encode(objName, "UTF-8").replace("+", "%20")));
    }
}
