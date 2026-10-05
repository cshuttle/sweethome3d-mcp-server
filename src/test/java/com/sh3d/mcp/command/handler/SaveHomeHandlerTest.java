package com.sh3d.mcp.command.handler;
import com.sh3d.mcp.command.CommandHandler;
import com.sh3d.mcp.command.CommandDescriptor;

import com.eteks.sweethome3d.io.HomeFileRecorder;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.Wall;
import com.sh3d.mcp.bridge.HomeSourceTracker;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Request;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class SaveHomeHandlerTest {

    private SaveHomeHandler handler;

    @BeforeEach
    void setUp() {
        handler = new SaveHomeHandler();
    }

    // --- Descriptor tests ---

    @Test
    void testImplementsInterfaces() {
        assertTrue(handler instanceof CommandHandler);
        assertTrue(handler instanceof CommandDescriptor);
    }

    @Test
    void testToolName() {
        assertNull(handler.getToolName());
    }

    @Test
    void testDescriptionNotEmpty() {
        String desc = handler.getDescription();
        assertNotNull(desc);
        assertFalse(desc.isEmpty());
        assertTrue(desc.toLowerCase().contains("save") || desc.contains(".sh3d"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSchemaStructure() {
        Map<String, Object> schema = handler.getSchema();
        assertEquals("object", schema.get("type"));

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertNotNull(properties);
        assertTrue(properties.containsKey("filePath"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSchemaFilePathProperty() {
        Map<String, Object> schema = handler.getSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> filePath = (Map<String, Object>) properties.get("filePath");

        assertEquals("string", filePath.get("type"));
        assertNotNull(filePath.get("description"));
        assertFalse(((String) filePath.get("description")).isEmpty());
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSchemaNoRequiredParams() {
        Map<String, Object> schema = handler.getSchema();
        List<String> required = (List<String>) schema.get("required");
        assertNotNull(required);
        assertTrue(required.isEmpty());
    }

    // --- Validation tests ---

    @Test
    void testNoFilePathAndNewHome() {
        Home home = new Home();
        // Home.getName() == null для нового дома
        HomeAccessor accessor = createAccessor(home);
        Request request = new Request("save_home", Collections.emptyMap());

        Response response = handler.execute(request, accessor);

        assertTrue(response.isError());
        assertTrue(response.getMessage().contains("No file path"));
    }

    @Test
    void testEmptyStringFilePathAndNewHome() {
        Home home = new Home();
        HomeAccessor accessor = createAccessor(home);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("filePath", "");
        Request request = new Request("save_home", params);

        Response response = handler.execute(request, accessor);

        assertTrue(response.isError());
        assertTrue(response.getMessage().contains("No file path"));
    }

    @Test
    void testBlankFilePathAndNewHome() {
        Home home = new Home();
        HomeAccessor accessor = createAccessor(home);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("filePath", "   ");
        Request request = new Request("save_home", params);

        Response response = handler.execute(request, accessor);

        assertTrue(response.isError());
        assertTrue(response.getMessage().contains("No file path"));
    }

    @Test
    void testNoPathErrorSaysToPassFilePath() {
        Home home = new Home();
        home.setRecovered(true);
        Response response = handler.execute(
                new Request("save_home", Collections.emptyMap()), createAccessor(home));

        assertTrue(response.isError());
        assertTrue(response.getMessage().contains("'filePath'"), response.getMessage());
        assertTrue(response.getMessage().contains("[Recovered]"), response.getMessage());
    }

    // --- Fallback to the opened file (house-model#90) ---

    @Test
    void testSavesToHomeName(@TempDir Path dir) {
        String file = dir.resolve("named.sh3d").toString();
        Home home = new Home();
        home.setName(file);
        home.setModified(true);

        Response response = handler.execute(
                new Request("save_home", Collections.emptyMap()), createAccessor(home));

        assertTrue(response.isOk(), response.getMessage());
        assertEquals(file, response.getData().get("filePath"));
        assertEquals("homeName", response.getData().get("pathSource"));
        assertTrue(Files.exists(Paths.get(file)));
        assertFalse(home.isModified());
    }

    @Test
    void testRecoveredHomeWithoutNameSavesToTheFileItWasOpenedFrom(@TempDir Path dir) throws Exception {
        // The original home, opened from house.sh3d, is tracked by the plugin.
        String original = dir.resolve("house.sh3d").toString();
        Home opened = new Home();
        opened.setName(original);
        HomeSourceTracker.track(opened);
        opened.addWall(new Wall(0, 0, 500, 0, 10, 250));
        opened.setModified(true);

        // Forced stop: the auto-save copy is all that is left; on restart Sweet Home 3D reads it,
        // marks it recovered and clears its name because house.sh3d is open in another window.
        String recoveryFile = dir.resolve("house.sh3d.recovered").toString();
        new HomeFileRecorder(0, false, null, false, true).writeHome(opened.clone(), recoveryFile);
        Home recovered = new HomeFileRecorder(0, false, null, false, true).readHome(recoveryFile);
        recovered.setRecovered(true);
        recovered.setName(null);
        recovered.setModified(true);

        Response response = handler.execute(
                new Request("save_home", Collections.emptyMap()), createAccessor(recovered));

        assertTrue(response.isOk(), response.getMessage());
        assertEquals(original, response.getData().get("filePath"));
        assertEquals("openedFile", response.getData().get("pathSource"));
        assertEquals(true, response.getData().get("wasRecovered"));
        // Like the app's own Save: named, not modified, no longer recovered.
        assertEquals(original, recovered.getName());
        assertFalse(recovered.isModified());
        assertFalse(recovered.isRecovered());
        // The file on disk holds the recovered content.
        Home onDisk = new HomeFileRecorder(0, false, null, false, true).readHome(original);
        assertEquals(1, onDisk.getWalls().size());
    }

    @Test
    void testExplicitFilePathWinsAndBecomesTheSource(@TempDir Path dir) {
        Home home = new Home();
        HomeSourceTracker.stamp(home, dir.resolve("old.sh3d").toString());
        String target = dir.resolve("new.sh3d").toString();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("filePath", target);

        Response response = handler.execute(new Request("save_home", params), createAccessor(home));

        assertTrue(response.isOk(), response.getMessage());
        assertEquals(target, response.getData().get("filePath"));
        assertEquals("parameter", response.getData().get("pathSource"));
        assertEquals(target, HomeSourceTracker.sourcePath(home));
        assertFalse(Files.exists(dir.resolve("old.sh3d")));
    }
}
