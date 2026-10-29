package com.atakmap.android.ghostmaps.plugin.core;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * What ATAK has loaded right now, as read by the platform layer. Immutable.
 * Paths are canonical so {@code /sdcard/…} and {@code /storage/emulated/0/…}
 * compare equal.
 */
public final class DeviceState {

    private final Set<String> loadedFiles;
    private final Set<String> iconSetUids;
    private final boolean storageAvailable;

    public DeviceState(Collection<String> loadedCanonicalPaths, Collection<String> iconSetUids,
            boolean storageAvailable) {
        this.loadedFiles = Collections.unmodifiableSet(new HashSet<>(loadedCanonicalPaths));
        this.iconSetUids = Collections.unmodifiableSet(new HashSet<>(iconSetUids));
        this.storageAvailable = storageAvailable;
    }

    /** Canonical paths of every file ATAK lists as loaded (File Overlays). */
    public Set<String> loadedFiles() {
        return loadedFiles;
    }

    public boolean isLoaded(String canonicalPath) {
        return loadedFiles.contains(canonicalPath);
    }

    public boolean hasIconSet(String uid) {
        return uid != null && iconSetUids.contains(uid);
    }

    /** False when external storage isn't mounted; no file may be judged missing then. */
    public boolean storageAvailable() {
        return storageAvailable;
    }
}
