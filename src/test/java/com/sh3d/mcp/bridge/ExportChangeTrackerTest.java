package com.sh3d.mcp.bridge;

import com.eteks.sweethome3d.model.CatalogDoorOrWindow;
import com.eteks.sweethome3d.model.CatalogPieceOfFurniture;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeDoorOrWindow;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.Sash;
import com.eteks.sweethome3d.model.Wall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ExportChangeTrackerTest {

    private Home home;
    private ExportChangeTracker tracker;

    @BeforeEach
    void setUp() {
        home = new Home();
        tracker = ExportChangeTracker.get();
    }

    private static HomePieceOfFurniture piece(String name) {
        return new HomePieceOfFurniture(new CatalogPieceOfFurniture(name, null, null, 50, 50, 50, true, false));
    }

    /** First export of a home (and any export without a base) is full. */
    @Test
    void firstExportIsFull() {
        assertTrue(tracker.snapshot(home, null).full);
        ExportChangeTracker.Snapshot s = tracker.snapshot(home, "not-a-token");
        assertTrue(s.full);
    }

    @Test
    void furnitureEditGivesDelta() {
        HomePieceOfFurniture sofa = piece("sofa");
        home.addPieceOfFurniture(sofa);
        String t = tracker.snapshot(home, null).token;
        sofa.setX(123);
        ExportChangeTracker.Snapshot s = tracker.snapshot(home, t);
        assertFalse(s.full);
        assertEquals(1, s.changedIds.size());
        assertTrue(s.changedIds.contains(sofa.getId()));
        assertTrue(s.removedIds.isEmpty());
        assertEquals(t, s.previousToken);
    }

    @Test
    void addAndDeleteAreTracked() {
        HomePieceOfFurniture old = piece("old");
        home.addPieceOfFurniture(old);
        String t = tracker.snapshot(home, null).token;
        HomePieceOfFurniture added = piece("new");
        home.addPieceOfFurniture(added);
        home.deletePieceOfFurniture(old);
        ExportChangeTracker.Snapshot s = tracker.snapshot(home, t);
        assertFalse(s.full);
        assertTrue(s.changedIds.contains(added.getId()));
        assertTrue(s.removedIds.contains(old.getId()));
        assertFalse(s.changedIds.contains(old.getId()));
    }

    @Test
    void wallChangeForcesFull() {
        Wall w = new Wall(0, 0, 100, 0, 10, 250);
        home.addWall(w);
        String t = tracker.snapshot(home, null).token;
        w.setHeight(260f);
        assertTrue(tracker.snapshot(home, t).full);
        String t2 = tracker.snapshot(home, null).token;
        home.addWall(new Wall(0, 0, 0, 100, 10, 250));
        assertTrue(tracker.snapshot(home, t2).full);
    }

    @Test
    void doorOrWindowForcesFull() {
        String t = tracker.snapshot(home, null).token;
        HomeDoorOrWindow door = new HomeDoorOrWindow(new CatalogDoorOrWindow(
                "test#door", "Door", null, null, null,
                90f, 10f, 210f, 0f, false, 1f, 0f, new Sash[0], null, null, true, null, null));
        assertTrue(door.isDoorOrWindow());
        home.addPieceOfFurniture(door);
        assertTrue(tracker.snapshot(home, t).full);
    }

    @Test
    void staleTokenForcesFull() {
        HomePieceOfFurniture p = piece("p");
        home.addPieceOfFurniture(p);
        String t1 = tracker.snapshot(home, null).token;
        tracker.snapshot(home, t1);           // someone else exported meanwhile
        p.setX(5);
        assertTrue(tracker.snapshot(home, t1).full);
    }

    @Test
    void anotherHomeStartsOver() {
        String t = tracker.snapshot(home, null).token;
        assertTrue(tracker.snapshot(new Home(), t).full);
    }

    @Test
    void prefixIsStableAndShort() {
        String p = ExportChangeTracker.prefix("pieceOfFurniture-1234");
        assertEquals(p, ExportChangeTracker.prefix("pieceOfFurniture-1234"));
        assertNotEquals(p, ExportChangeTracker.prefix("pieceOfFurniture-1235"));
        assertTrue(p.matches("f_[0-9a-f]{12}"));
    }
}
