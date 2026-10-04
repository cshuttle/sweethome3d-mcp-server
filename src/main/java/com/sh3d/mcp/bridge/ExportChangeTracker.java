package com.sh3d.mcp.bridge;

import com.eteks.sweethome3d.model.CollectionEvent;
import com.eteks.sweethome3d.model.CollectionListener;
import com.eteks.sweethome3d.model.Home;
import com.eteks.sweethome3d.model.HomeEnvironment;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeObject;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;

import java.beans.PropertyChangeListener;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks what changed in the live {@link Home} since the last {@code export_to_obj}, so the next
 * export can write only the furniture that changed (a delta) instead of the whole house.
 *
 * <p>Furniture that is not a door or window can be exported on its own: its geometry depends on
 * nothing else. Everything else forces a full export: a door or window cuts holes in walls, walls
 * join their neighbours, rooms and the ground are computed from the walls and furniture, and the
 * environment changes every material. Any add, delete or property change of those sets
 * {@code fullNeeded}.
 *
 * <p>Listeners run on the EDT; {@link #snapshot} must be called on the EDT too (it is, inside the
 * same runOnEDT that clones the home), so no change can fall between the snapshot and the clone.
 */
public final class ExportChangeTracker {

    /** What an export must write, and the token a client passes back to ask for the next delta. */
    public static final class Snapshot {
        public final String token;
        public final String previousToken;
        public final boolean full;
        public final Set<String> changedIds;   // top-level furniture ids, still in the home
        public final Set<String> removedIds;   // top-level furniture ids, deleted since

        Snapshot(String token, String previousToken, boolean full, Set<String> changed, Set<String> removed) {
            this.token = token;
            this.previousToken = previousToken;
            this.full = full;
            this.changedIds = changed;
            this.removedIds = removed;
        }
    }

    private static final ExportChangeTracker INSTANCE = new ExportChangeTracker();

    public static ExportChangeTracker get() {
        return INSTANCE;
    }

    private Home home;
    private String token;
    private boolean fullNeeded = true;
    private final Set<String> changed = new LinkedHashSet<>();
    private final Set<String> removed = new LinkedHashSet<>();

    private ExportChangeTracker() {
    }

    /**
     * Called on the EDT. Decides full or delta for an export requested with {@code deltaBase}, and
     * starts a new tracking round. A delta is possible only when the client's base is the last
     * export of this same home and nothing but plain furniture changed since.
     */
    public synchronized Snapshot snapshot(Home current, String deltaBase) {
        if (current != home) {
            attach(current);
        }
        boolean full = fullNeeded || deltaBase == null || token == null || !token.equals(deltaBase);
        Snapshot s = new Snapshot(UUID.randomUUID().toString(), token, full,
                new LinkedHashSet<>(changed), new LinkedHashSet<>(removed));
        token = s.token;
        fullNeeded = false;
        changed.clear();
        removed.clear();
        return s;
    }

    /** The OBJ group-name prefix for a top-level piece: stable across exports, short enough for Blender's 63 characters. */
    public static String prefix(String id) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("f_");
            for (int i = 0; i < 6; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void attach(Home h) {
        home = h;
        token = null;
        fullNeeded = true;
        changed.clear();
        removed.clear();
        PropertyChangeListener structural = ev -> markFull();
        h.addFurnitureListener(this::furnitureEvent);
        for (HomePieceOfFurniture p : h.getFurniture()) {
            watchPiece(p, p.getId());
        }
        CollectionListener<HomeObject> structuralCollection = ev -> {
            markFull();
            if (ev.getType() == CollectionEvent.Type.ADD) {
                ev.getItem().addPropertyChangeListener(structural);
            }
        };
        h.addWallsListener(ev -> structuralCollection.collectionChanged(cast(ev)));
        h.addRoomsListener(ev -> structuralCollection.collectionChanged(cast(ev)));
        h.addLevelsListener(ev -> structuralCollection.collectionChanged(cast(ev)));
        h.addPolylinesListener(ev -> structuralCollection.collectionChanged(cast(ev)));
        h.addLabelsListener(ev -> structuralCollection.collectionChanged(cast(ev)));
        watchAll(h.getWalls(), structural);
        watchAll(h.getRooms(), structural);
        watchAll(h.getLevels(), structural);
        watchAll(h.getPolylines(), structural);
        watchAll(h.getLabels(), structural);
        HomeEnvironment env = h.getEnvironment();
        for (HomeEnvironment.Property p : HomeEnvironment.Property.values()) {
            env.addPropertyChangeListener(p, structural);
        }
    }

    @SuppressWarnings("unchecked")
    private static CollectionEvent<HomeObject> cast(CollectionEvent<?> ev) {
        return (CollectionEvent<HomeObject>) ev;
    }

    private static void watchAll(Collection<? extends HomeObject> items, PropertyChangeListener l) {
        for (HomeObject o : items) {
            o.addPropertyChangeListener(l);
        }
    }

    private void furnitureEvent(CollectionEvent<HomePieceOfFurniture> ev) {
        HomePieceOfFurniture p = ev.getItem();
        if (ev.getType() == CollectionEvent.Type.ADD) {
            watchPiece(p, p.getId());
            pieceChanged(p, p.getId());
        } else {
            synchronized (this) {
                if (isDoorOrWindow(p)) {
                    fullNeeded = true;
                }
                changed.remove(p.getId());
                removed.add(p.getId());
            }
        }
    }

    /** Watch a top-level piece, and every piece inside it if it is a group, under the top-level id. */
    private void watchPiece(HomePieceOfFurniture p, String topId) {
        p.addPropertyChangeListener(ev -> pieceChanged(p, topId));
        if (p instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) p).getAllFurniture()) {
                child.addPropertyChangeListener(ev -> pieceChanged(child, topId));
            }
        }
    }

    private synchronized void pieceChanged(HomePieceOfFurniture p, String topId) {
        if (isDoorOrWindow(p)) {
            fullNeeded = true;
        }
        removed.remove(topId);
        changed.add(topId);
    }

    private static boolean isDoorOrWindow(HomePieceOfFurniture p) {
        if (p.isDoorOrWindow()) {
            return true;
        }
        if (p instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture c : ((HomeFurnitureGroup) p).getAllFurniture()) {
                if (c.isDoorOrWindow()) {
                    return true;
                }
            }
        }
        return false;
    }

    private synchronized void markFull() {
        fullNeeded = true;
    }
}
