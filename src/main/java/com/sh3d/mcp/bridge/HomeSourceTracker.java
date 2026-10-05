package com.sh3d.mcp.bridge;

import com.eteks.sweethome3d.model.Home;

/**
 * Remembers, inside the home itself, the file a home was opened from or last saved to.
 *
 * <p>After a forced stop Sweet Home 3D reopens the auto-saved copy of each modified home
 * ({@code AutoRecoveryManager}). When the original file is open as well (for example because the
 * application was started with {@code -open house.sh3d}), the recovered copy loses its name
 * ({@code Home.setName(null)}) and shows as "[Recovered]", so a plain save has nowhere to go.
 *
 * <p>Home properties are cloned into every auto-save and written to the {@code .sh3d} file, so a
 * path stamped here survives into the recovered copy even when its name is cleared. The stamp only
 * changes when the home gets a new non-empty name; it never marks the home modified.
 */
public final class HomeSourceTracker {

    /** Home property holding the absolute path of the file the home was opened from or saved to. */
    public static final String SOURCE_PATH_PROPERTY = "com.sh3d.mcp.sourcePath";

    private HomeSourceTracker() {
    }

    /**
     * Stamps the home's current name and keeps the stamp in step with later name changes
     * (Save As in the application, {@code save_home}, {@code load_home}). Call on the EDT.
     */
    public static void track(Home home) {
        stamp(home, home.getName());
        home.addPropertyChangeListener(Home.Property.NAME,
                event -> stamp(home, (String) event.getNewValue()));
    }

    /** Records {@code path} as the home's source file; a null or blank path leaves the stamp alone. */
    public static void stamp(Home home, String path) {
        if (path == null || path.trim().isEmpty()) {
            return;
        }
        if (!path.equals(home.getProperty(SOURCE_PATH_PROPERTY))) {
            home.setProperty(SOURCE_PATH_PROPERTY, path);
        }
    }

    /** Returns the stamped source file, or null when the plugin never saw this home with a name. */
    public static String sourcePath(Home home) {
        String path = home.getProperty(SOURCE_PATH_PROPERTY);
        return path == null || path.trim().isEmpty() ? null : path;
    }
}
