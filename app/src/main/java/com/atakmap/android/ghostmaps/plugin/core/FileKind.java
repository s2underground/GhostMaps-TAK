package com.atakmap.android.ghostmaps.plugin.core;

import java.util.Locale;

/**
 * The kinds of published file GhostMaps knows how to install. Anything else
 * in the repository is ignored: a type is only offered once it has a handler
 * that installs, detects and removes it safely.
 */
public enum FileKind {
    /** KMZ/KML overlay, loaded by ATAK's KML importer; appears in File Overlays. */
    MAP_LAYER,
    /** ZIP of marker icons, loaded into ATAK's icon sets (Point Dropper). */
    ICON_SET;

    /** Kind for a file name by extension, or null if GhostMaps can't install it. */
    public static FileKind fromFileName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        if (n.endsWith(".kmz") || n.endsWith(".kml"))
            return MAP_LAYER;
        if (n.endsWith(".zip"))
            return ICON_SET; // content is validated after download (IconSetInspector)
        return null;
    }
}
