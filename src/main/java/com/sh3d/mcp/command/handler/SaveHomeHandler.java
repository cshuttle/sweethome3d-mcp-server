package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;

import com.eteks.sweethome3d.io.HomeFileRecorder;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.RecorderException;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;

import com.sh3d.mcp.bridge.HomeSourceTracker;
import com.sh3d.mcp.bridge.PathValidator;

import com.sh3d.mcp.command.util.SchemaBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Обработчик команды "save_home".
 * Сохраняет текущую сцену в .sh3d файл через HomeFileRecorder.
 *
 * <pre>
 * Параметры:
 *   filePath (optional) — путь к файлу. Если не указан, используется Home.getName(),
 *                         а если у дома нет имени (например, "[Recovered]" после принудительной
 *                         остановки) — файл, из которого дом был открыт ({@link HomeSourceTracker}).
 * Возвращает:
 *   filePath — абсолютный путь к сохранённому файлу
 *   sizeBytes — размер файла в байтах
 *   pathSource — откуда взят путь: "parameter", "homeName" или "openedFile"
 *   wasRecovered — true, если сохранена восстановленная ("[Recovered]") копия
 * </pre>
 */
public class SaveHomeHandler implements CommandHandler, CommandDescriptor {

    private static final Logger LOG = Logger.getLogger(SaveHomeHandler.class.getName());

    private static final int COMPRESSION_LEVEL = 9;

    @Override
    public Response execute(Request request, HomeAccessor accessor) {
        // 1. Определяем путь к файлу
        String filePath = request.getString("filePath");
        String pathSource = "parameter";
        if (filePath == null || filePath.trim().isEmpty()) {
            String[] resolved = accessor.runOnEDT(() -> resolveCurrentFile(accessor.getHome()));
            if (resolved == null) {
                return Response.error(
                        "No file path specified, and this home has no file to save to: it is untitled, "
                                + "or a '[Recovered]' copy whose name Sweet Home 3D cleared because the "
                                + "original file is open in another window, and the plugin has no record "
                                + "of the file it came from. Call save_home again with 'filePath' set to "
                                + "the absolute path of the .sh3d file to write (for a recovered home, "
                                + "the file it was opened from).");
            }
            filePath = resolved[0];
            pathSource = resolved[1];
        }

        // 2. Нормализация пути + расширение + создание директорий
        Path path;
        try {
            path = PathValidator.validateAndNormalize(filePath, ".sh3d");
        } catch (Exception e) {
            return Response.error("Cannot create directory: " + e.getMessage());
        }
        String normalizedPath = path.toString();

        // 4. Клонируем Home на EDT
        Home clonedHome = accessor.runOnEDT(() -> accessor.getHome().clone());

        // 5. Записываем файл вне EDT
        try {
            // preferXmlEntry: also write Home.xml, as the app's own Save does, so tools that
            // read the file without Java (cshuttle/homeassistant#73) see the same home.
            HomeFileRecorder recorder = new HomeFileRecorder(COMPRESSION_LEVEL, false, null, false, true);
            recorder.writeHome(clonedHome, normalizedPath);

            // 6. Обновляем состояние оригинального Home на EDT (как HomeController.save:
            //    имя, modified=false, recovered=false — последнее удаляет файл автовосстановления)
            boolean wasRecovered = accessor.runOnEDT(() -> {
                Home home = accessor.getHome();
                boolean recovered = home.isRecovered();
                home.setName(normalizedPath);
                HomeSourceTracker.stamp(home, normalizedPath);
                home.setModified(false);
                home.setRecovered(false);
                return recovered;
            });

            // 7. Результат
            long sizeBytes = Files.size(path);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", normalizedPath);
            data.put("sizeBytes", sizeBytes);
            data.put("pathSource", pathSource);
            data.put("wasRecovered", wasRecovered);

            LOG.info("Home saved: " + normalizedPath + " (" + sizeBytes + " bytes)");
            return Response.ok(data);

        } catch (RecorderException e) {
            LOG.log(Level.WARNING, "Save failed", e);
            return Response.error("Save failed: " + e.getMessage());
        } catch (OutOfMemoryError e) {
            LOG.log(Level.SEVERE, "OOM during save", e);
            return Response.error("Out of memory during save — reduce scene complexity");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Save failed", e);
            return Response.error("Save failed: " + e.getMessage());
        }
    }

    /**
     * Returns {path, source} for a save without filePath, or null when no file is known.
     * The home's own name wins; a home without one (a "[Recovered]" copy whose name was cleared
     * because its original is open too) falls back to the file the plugin saw it opened from.
     */
    static String[] resolveCurrentFile(Home home) {
        String name = home.getName();
        if (name != null && !name.trim().isEmpty()) {
            return new String[]{name, "homeName"};
        }
        String source = HomeSourceTracker.sourcePath(home);
        if (source != null) {
            return new String[]{source, "openedFile"};
        }
        return null;
    }

    @Override
    public String getDescription() {
        return "Saves the current home to a .sh3d file on disk. "
                + "If filePath is provided, saves to that location (Save As). "
                + "If filePath is omitted, saves to the current file path; a home with no name "
                + "(e.g. a '[Recovered]' copy after a forced stop) is saved to the file it was "
                + "opened from when the plugin knows it, otherwise filePath is required. "
                + "Saving clears the modified and recovered flags, like the app's own Save. "
                + "Returns the absolute path, file size in bytes, and where the path came from.";
    }

    @Override
    public Map<String, Object> getSchema() {
        return SchemaBuilder.create()
                .string("filePath",
                        "Absolute path for the .sh3d file. "
                                + "If omitted, saves to the current file (Home > Save). "
                                + "The .sh3d extension is added automatically if missing.")
                .build();
    }
}
