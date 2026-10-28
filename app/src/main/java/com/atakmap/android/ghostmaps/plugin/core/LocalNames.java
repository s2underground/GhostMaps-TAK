package com.atakmap.android.ghostmaps.plugin.core;

import java.util.Locale;
import java.util.Set;

/** Picks the on-device file name for a download. */
public final class LocalNames {

    private LocalNames() {
    }

    /**
     * {@code fileName}, or "name (2).kmz", "name (3).kmz" … if that name is
     * taken. {@code takenLower} holds names in use, lower-cased. Characters
     * Android file systems reject are replaced.
     */
    public static String unique(String fileName, Set<String> takenLower) {
        String clean = fileName.replaceAll("[\\\\/:*?\"<>|\\x00-\\x1f]", "_").trim();
        if (clean.isEmpty() || clean.startsWith("."))
            clean = "file" + clean;
        if (!takenLower.contains(clean.toLowerCase(Locale.US)))
            return clean;
        int dot = clean.lastIndexOf('.');
        String stem = dot > 0 ? clean.substring(0, dot) : clean;
        String ext = dot > 0 ? clean.substring(dot) : "";
        for (int i = 2;; i++) {
            String n = stem + " (" + i + ")" + ext;
            if (!takenLower.contains(n.toLowerCase(Locale.US)))
                return n;
        }
    }
}
