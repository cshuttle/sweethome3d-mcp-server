package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;

import com.eteks.sweethome3d.j3d.Ground3D;
import com.eteks.sweethome3d.j3d.OBJWriter;
import com.eteks.sweethome3d.j3d.Object3DBranchFactory;
import com.eteks.sweethome3d.model.DimensionLine;
import com.eteks.sweethome3d.model.Elevatable;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Selectable;
import com.sh3d.mcp.bridge.ExportChangeTracker;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.command.util.SchemaBuilder;
import com.sh3d.mcp.bridge.PathValidator;

import javax.media.j3d.Node;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Обработчик команды "export_to_obj".
 * Экспортирует 3D-сцену в формат Wavefront OBJ (ZIP-архив с OBJ + MTL + текстурами).
 * Воспроизводит логику HomePane.OBJExporter.exportHomeToFile() через публичные API:
 * OBJWriter, Object3DBranchFactory, Ground3D.
 *
 * <pre>
 * Параметры: нет
 * Возвращает: base64-encoded ZIP с OBJ + MTL + текстурами
 * </pre>
 */
public class ExportToObjHandler implements CommandHandler, CommandDescriptor {

    private static final Logger LOG = Logger.getLogger(ExportToObjHandler.class.getName());

    private static final String OBJ_FILENAME = "export.obj";
    private static final String OBJ_HEADER = "Sweet Home 3D MCP Plugin - OBJ Export";

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        String dirPath = request.getString("dirPath");
        String deltaBase = request.getString("deltaBase");
        if (deltaBase != null && (dirPath == null || dirPath.trim().isEmpty())) {
            return Response.error("deltaBase needs dirPath");
        }
        // 1. Клонируем Home в EDT (чтобы не мутировать оригинал). The change snapshot is taken in
        // the same EDT task, so no edit can fall between what the clone shows and what is tracked.
        Object[] cloned = accessor.runOnEDT(() -> new Object[] {
                accessor.getHome().clone(), ExportChangeTracker.get().snapshot(accessor.getHome(), deltaBase)});
        Home clonedHome = (Home) cloned[0];
        ExportChangeTracker.Snapshot snap = (ExportChangeTracker.Snapshot) cloned[1];

        Path tempDir = null;
        OBJWriter writer = null;
        try {
            // dirPath mode writes the files straight into an empty directory: no zip, no in-memory copy.
            if (dirPath != null && !dirPath.trim().isEmpty()) {
                Path dir = PathValidator.normalizeOnly(dirPath);
                Files.createDirectories(dir);
                try (DirectoryStream<Path> existing = Files.newDirectoryStream(dir)) {
                    if (existing.iterator().hasNext()) {
                        return Response.error("dirPath must be an empty directory: " + dir);
                    }
                }
                writer = new OBJWriter(dir.resolve(OBJ_FILENAME).toString(), OBJ_HEADER, -1);
                List<String> exported = exportHome(clonedHome, writer, snap);
                writer.close();
                writer = null;
                return Response.ok(result(snap, dir, exported));
            }

            // 2. Создаём временную директорию
            tempDir = Files.createTempDirectory("sh3d-obj-");
            String objFilePath = tempDir.resolve(OBJ_FILENAME).toString();

            // 3. Экспортируем вне EDT (тяжёлая операция — создание 3D-геометрии)
            writer = new OBJWriter(objFilePath, OBJ_HEADER, -1);
            exportHome(clonedHome, writer, null);
            writer.close();
            writer = null;

            // 4. Упаковываем все файлы в ZIP
            ByteArrayOutputStream zipBaos = new ByteArrayOutputStream();
            int fileCount = 0;
            try (ZipOutputStream zos = new ZipOutputStream(zipBaos);
                 DirectoryStream<Path> stream = Files.newDirectoryStream(tempDir)) {
                // Level 1: the OBJ is ~260 MB of text; the default level 6 spent ~3 s more for little gain.
                zos.setLevel(Deflater.BEST_SPEED);
                for (Path file : stream) {
                    if (Files.isRegularFile(file)) {
                        zos.putNextEntry(new ZipEntry(file.getFileName().toString()));
                        Files.copy(file, zos);
                        zos.closeEntry();
                        fileCount++;
                    }
                }
            }

            // 5. filePath mode: save to disk and return metadata without base64
            String filePath = request.getString("filePath");
            if (filePath != null && !filePath.trim().isEmpty()) {
                Path path = PathValidator.validateAndNormalize(filePath, ".zip");
                Files.write(path, zipBaos.toByteArray());

                Map<String, Object> data = new LinkedHashMap<>();
                data.put("filePath", path.toString());
                data.put("file_count", fileCount);
                data.put("size_bytes", (int) Files.size(path));

                LOG.info("Exported OBJ to " + path + ": " + fileCount + " files, "
                        + Files.size(path) + " bytes");
                return Response.ok(data);
            }

            // 6. Inline mode: base64 (default)
            String base64 = Base64.getEncoder().encodeToString(zipBaos.toByteArray());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("obj_zip_base64", base64);
            data.put("file_count", fileCount);
            data.put("size_bytes", zipBaos.size());

            LOG.info("Exported OBJ: " + fileCount + " files, "
                    + zipBaos.size() + " bytes (ZIP)");

            return Response.ok(data);
        } catch (OutOfMemoryError e) {
            LOG.log(Level.SEVERE, "OOM during OBJ export", e);
            return Response.error("Out of memory during OBJ export");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "OBJ export failed", e);
            return Response.error("OBJ export failed: " + e.getMessage());
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
            if (tempDir != null) {
                cleanupTempDir(tempDir);
            }
        }
    }

    private static Map<String, Object> result(ExportChangeTracker.Snapshot snap, Path dir, List<String> exported)
            throws IOException {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dirPath", dir.toString());
        data.put("mode", snap.full ? "full" : "delta");
        data.put("token", snap.token);
        if (!snap.full) {
            data.put("base", snap.previousToken);
            List<Object> changed = new ArrayList<>();
            for (String id : snap.changedIds) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("id", id);
                c.put("prefix", ExportChangeTracker.prefix(id));
                c.put("exported", exported.contains(id));
                changed.add(c);
            }
            List<Object> removed = new ArrayList<>();
            for (String id : snap.removedIds) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", id);
                r.put("prefix", ExportChangeTracker.prefix(id));
                removed.add(r);
            }
            data.put("changed", changed);
            data.put("removed", removed);
        }
        int files = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path ignored : stream) files++;
        }
        data.put("file_count", files);
        LOG.info("Exported OBJ (" + data.get("mode") + ") to " + dir + ": " + files + " files");
        return data;
    }

    /**
     * Экспортирует все объекты Home в OBJWriter.
     * Логика воспроизведена из SH3D HomePane.OBJExporter.exportHomeToFile().
     *
     * <p>Furniture is written under {@link ExportChangeTracker#prefix} of its top-level piece's id,
     * so a later delta can replace exactly those objects. With a delta snapshot only the changed
     * top-level pieces are written, and no ground. Returns the top-level ids written.
     */
    private List<String> exportHome(Home home, OBJWriter writer, ExportChangeTracker.Snapshot snap) throws IOException {
        boolean delta = snap != null && !snap.full;
        List<String> written = new ArrayList<>();
        Object3DBranchFactory factory = new Object3DBranchFactory();

        // Собираем все видимые элементы
        List<Selectable> items = new ArrayList<>(home.getSelectableViewableItems());

        // Разворачиваем HomeFurnitureGroup в отдельные элементы, remembering each piece's top-level id
        Map<HomePieceOfFurniture, String> topIds = new java.util.IdentityHashMap<>();
        List<HomePieceOfFurniture> ungroupedFurniture = new ArrayList<>();
        for (Iterator<Selectable> it = items.iterator(); it.hasNext(); ) {
            Selectable item = it.next();
            if (item instanceof HomeFurnitureGroup) {
                it.remove();
                for (HomePieceOfFurniture piece : ((HomeFurnitureGroup) item).getAllFurniture()) {
                    if (!(piece instanceof HomeFurnitureGroup)) {
                        ungroupedFurniture.add(piece);
                        topIds.put(piece, ((HomeFurnitureGroup) item).getId());
                    }
                }
            } else if (item instanceof HomePieceOfFurniture) {
                topIds.put((HomePieceOfFurniture) item, ((HomePieceOfFurniture) item).getId());
            }
        }
        items.addAll(ungroupedFurniture);
        if (delta) {
            items.removeIf(i -> !(i instanceof HomePieceOfFurniture)
                    || !snap.changedIds.contains(topIds.get(i)));
        }

        // Очищаем выделение (влияет на экспорт)
        home.setSelectedItems(Collections.emptyList());

        // Делаем все viewable уровни видимыми
        for (com.eteks.sweethome3d.model.Level level : home.getLevels()) {
            if (level.isViewable()) {
                level.setVisible(true);
            }
        }

        // Добавляем землю (ground)
        Rectangle2D bounds = delta ? null : getExportedHomeBounds(home);
        if (bounds != null) {
            Ground3D ground = new Ground3D(home,
                    (float) bounds.getX(), (float) bounds.getY(),
                    (float) bounds.getWidth(), (float) bounds.getHeight(),
                    true);
            writer.writeNode(ground, "ground");
        }

        // Экспортируем каждый элемент
        int counter = 0;
        for (Selectable item : items) {
            Node node = (Node) factory.createObject3D(home, item, true);
            if (node != null) {
                if (item instanceof HomePieceOfFurniture) {
                    String topId = topIds.get(item);
                    writer.writeNode(node, ExportChangeTracker.prefix(topId));
                    if (!written.contains(topId)) written.add(topId);
                } else if (!(item instanceof DimensionLine)) {
                    String name = item.getClass().getSimpleName().toLowerCase() + "_" + (++counter);
                    writer.writeNode(node, name);
                }
            }
        }
        return written;
    }

    /**
     * Вычисляет bounding box всех экспортируемых объектов.
     * Логика воспроизведена из SH3D HomePane.OBJExporter.getExportedHomeBounds().
     */
    private Rectangle2D getExportedHomeBounds(Home home) {
        Rectangle2D bounds = null;

        // Bounds стен
        bounds = updateBounds(bounds, home.getWalls());

        // Bounds мебели
        for (HomePieceOfFurniture piece : home.getFurniture()) {
            if (!piece.isVisible()) continue;
            if (piece.getLevel() != null && !piece.getLevel().isViewable()) continue;

            if (piece instanceof HomeFurnitureGroup) {
                for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                    if (child.isVisible()) {
                        bounds = addPointsBounds(bounds, child.getPoints());
                    }
                }
            } else {
                bounds = addPointsBounds(bounds, piece.getPoints());
            }
        }

        // Bounds комнат
        bounds = updateBounds(bounds, home.getRooms());

        return bounds;
    }

    private Rectangle2D updateBounds(Rectangle2D bounds, java.util.Collection<? extends Selectable> items) {
        for (Selectable item : items) {
            if (item instanceof Elevatable) {
                com.eteks.sweethome3d.model.Level level = ((Elevatable) item).getLevel();
                if (level != null && !level.isViewableAndVisible()) {
                    continue;
                }
            }
            bounds = addPointsBounds(bounds, item.getPoints());
        }
        return bounds;
    }

    private Rectangle2D addPointsBounds(Rectangle2D bounds, float[][] points) {
        for (float[] point : points) {
            if (bounds == null) {
                bounds = new Rectangle2D.Float(point[0], point[1], 0, 0);
            } else {
                bounds.add(point[0], point[1]);
            }
        }
        return bounds;
    }

    private void cleanupTempDir(Path dir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            LOG.log(Level.FINE, "Failed to cleanup temp dir: " + dir, e);
        }
    }

    @Override
    public String getDescription() {
        return "Exports the entire 3D scene to Wavefront OBJ format. "
                + "Returns a base64-encoded ZIP archive containing the OBJ file, "
                + "MTL material definitions, and texture images. "
                + "The exported model includes walls, rooms, furniture, ground, "
                + "and all applied materials/textures. "
                + "If 'filePath' is provided, saves the ZIP archive to disk and returns only metadata (no base64). "
                + "This is recommended for large scenes to avoid oversized responses. "
                + "If 'dirPath' (an empty directory) is given instead, the files are written there unzipped "
                + "and the result carries a 'token'. Passing that token back as 'deltaBase' exports only the "
                + "furniture that changed since (mode 'delta', with 'changed' and 'removed' pieces and their "
                + "OBJ group 'prefix'), or everything (mode 'full') when walls, rooms, levels, doors/windows, "
                + "labels or the environment changed, or the token is not the last export's. "
                + "Furniture groups are named by that prefix in every export.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .string("filePath",
                        "Absolute path to save the ZIP file. Extension is auto-corrected to .zip. "
                                + "If provided, returns metadata only (no base64 data).")
                .string("dirPath",
                        "Absolute path of an EMPTY directory to write the OBJ, MTL and textures into, unzipped. "
                                + "Returns a 'token' for deltaBase.")
                .string("deltaBase",
                        "The 'token' of the previous dirPath export. Exports only furniture changed since, "
                                + "or everything when a delta is not possible (see 'mode'). Needs dirPath.")
                .build();
    }
}
