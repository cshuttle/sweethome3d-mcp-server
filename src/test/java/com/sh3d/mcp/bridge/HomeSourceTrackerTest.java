package com.sh3d.mcp.bridge;

import com.eteks.sweethome3d.io.HomeFileRecorder;
import com.eteks.sweethome3d.model.Home;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class HomeSourceTrackerTest {

    @Test
    void trackStampsTheCurrentName() {
        Home home = new Home();
        home.setName("/homes/house.sh3d");
        HomeSourceTracker.track(home);
        assertEquals("/homes/house.sh3d", HomeSourceTracker.sourcePath(home));
    }

    @Test
    void untitledHomeHasNoSource() {
        Home home = new Home();
        HomeSourceTracker.track(home);
        assertNull(HomeSourceTracker.sourcePath(home));
    }

    @Test
    void followsLaterNameChanges() {
        Home home = new Home();
        HomeSourceTracker.track(home);
        home.setName("/homes/a.sh3d");
        assertEquals("/homes/a.sh3d", HomeSourceTracker.sourcePath(home));
        home.setName("/homes/b.sh3d");
        assertEquals("/homes/b.sh3d", HomeSourceTracker.sourcePath(home));
    }

    @Test
    void clearingTheNameKeepsTheStamp() {
        // AutoRecoveryManager.openRecoveredHomes() calls setName(null) on a recovered copy whose
        // original is open too: the stamp must survive that.
        Home home = new Home();
        home.setName("/homes/house.sh3d");
        HomeSourceTracker.track(home);
        home.setName(null);
        assertEquals("/homes/house.sh3d", HomeSourceTracker.sourcePath(home));
    }

    @Test
    void stampingDoesNotMarkTheHomeModified() {
        Home home = new Home();
        home.setName("/homes/house.sh3d");
        HomeSourceTracker.track(home);
        assertFalse(home.isModified());
    }

    @Test
    void blankPathIsIgnored() {
        Home home = new Home();
        HomeSourceTracker.stamp(home, "  ");
        assertNull(HomeSourceTracker.sourcePath(home));
    }

    @Test
    void stampSurvivesAnAutoSaveRoundTrip(@TempDir Path dir) throws Exception {
        // The recovery auto-save writes a clone of the home with the app's HomeRecorder;
        // the recovered copy is read back from that file.
        Home home = new Home();
        home.setName("/homes/house.sh3d");
        HomeSourceTracker.track(home);

        String recovered = dir.resolve("house.sh3d.recovered").toString();
        new HomeFileRecorder(0, false, null, false, true).writeHome(home.clone(), recovered);
        Home read = new HomeFileRecorder(0, false, null, false, true).readHome(recovered);

        assertEquals("/homes/house.sh3d", HomeSourceTracker.sourcePath(read));
    }
}
