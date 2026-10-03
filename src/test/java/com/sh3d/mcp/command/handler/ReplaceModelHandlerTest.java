package com.sh3d.mcp.command.handler;

import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.sh3d.mcp.bridge.HomeAccessor;
import com.sh3d.mcp.protocol.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.sh3d.mcp.command.handler.ImportFurnitureHandlerTest.fixture;
import static com.sh3d.mcp.command.handler.ImportFurnitureHandlerTest.request;
import static com.sh3d.mcp.command.handler.TestFixtures.addFurniture;
import static com.sh3d.mcp.command.handler.TestFixtures.createAccessor;
import static org.junit.jupiter.api.Assertions.*;

class ReplaceModelHandlerTest {

    private ReplaceModelHandler handler;
    private HomeAccessor accessor;
    private Home home;
    private HomePieceOfFurniture piece;

    @BeforeEach
    void setUp() {
        handler = new ReplaceModelHandler();
        home = new Home();
        accessor = createAccessor(home);
        piece = addFurniture(home, "Chair", 300, 400);
        piece.setWidth(40);
        piece.setDepth(60);
        piece.setHeight(90);
        piece.setAngle((float) Math.toRadians(30));
    }

    @Test
    void testMissingId() throws Exception {
        Response resp = handler.execute(request("filePath", fixture()), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("id"));
    }

    @Test
    void testMissingFilePath() {
        Response resp = handler.execute(request("id", piece.getId()), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("filePath"));
    }

    @Test
    void testUnknownId() throws Exception {
        Response resp = handler.execute(request("id", "nope", "filePath", fixture()), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("not found"));
    }

    @Test
    void testFileNotFound() {
        Content before = piece.getModel();
        Response resp = handler.execute(request("id", piece.getId(), "filePath", "/nonexistent/x.obj"), accessor);
        assertTrue(resp.isError());
        assertSame(before, piece.getModel());
    }

    @Test
    void testGroupRefused() throws Exception {
        HomePieceOfFurniture other = addFurniture(home, "Table", 0, 0);
        home.deletePieceOfFurniture(piece);
        home.deletePieceOfFurniture(other);
        HomeFurnitureGroup group = new HomeFurnitureGroup(Arrays.asList(piece, other), "Set");
        home.addPieceOfFurniture(group);

        Response resp = handler.execute(request("id", group.getId(), "filePath", fixture()), accessor);
        assertTrue(resp.isError());
        assertTrue(resp.getMessage().contains("group"));
    }

    @Test
    void testKeepSize() throws Exception {
        Content before = piece.getModel();
        Response resp = handler.execute(request("id", piece.getId(), "filePath", fixture()), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        assertNotEquals(before, piece.getModel());
        assertEquals(piece.getId(), resp.getData().get("id"));
        assertEquals(40f, piece.getWidth(), 0.01f);
        assertEquals(60f, piece.getDepth(), 0.01f);
        assertEquals(90f, piece.getHeight(), 0.01f);
        assertEquals(300f, piece.getX(), 0.01f);
        assertEquals(400f, piece.getY(), 0.01f);
        assertEquals(Math.toRadians(30), piece.getAngle(), 0.01);
        assertNull(piece.getModelMaterials());
    }

    @Test
    void testNaturalProportionsAtCurrentWidth() throws Exception {
        Response resp = handler.execute(
                request("id", piece.getId(), "filePath", fixture(), "keepSize", false), accessor);

        assertTrue(resp.isOk(), resp.getMessage());
        // Fixture is 100 x 50 x 80; scaled to the current width of 40
        assertEquals(40f, piece.getWidth(), 0.01f);
        assertEquals(20f, piece.getDepth(), 0.01f);
        assertEquals(32f, piece.getHeight(), 0.01f);
        assertEquals(32.0, ((Number) resp.getData().get("height")).doubleValue(), 0.01);
    }

    @Test
    void testReplaceTwiceGivesNewUrls() throws Exception {
        handler.execute(request("id", piece.getId(), "filePath", fixture()), accessor);
        Content first = piece.getModel();
        handler.execute(request("id", piece.getId(), "filePath", fixture()), accessor);
        assertNotEquals(first, piece.getModel());
    }
}
