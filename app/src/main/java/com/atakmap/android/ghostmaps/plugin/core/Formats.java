package com.atakmap.android.ghostmaps.plugin.core;

import java.util.Locale;

/** Display helpers shared by the pane; locale-independent so tests are stable. */
public final class Formats {

    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private Formats() {
    }

    /** "850 KB", "3.4 MB", "62 MB" ("?" when unknown). */
    public static String size(long bytes) {
        if (bytes < 0)
            return "?";
        if (bytes < 1024)
            return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1000)
            return String.format(Locale.US, "%.0f KB", Math.max(1, kb));
        double mb = kb / 1024.0;
        if (mb < 10)
            return String.format(Locale.US, "%.1f MB", mb);
        if (mb < 1000)
            return String.format(Locale.US, "%.0f MB", mb);
        return String.format(Locale.US, "%.1f GB", mb / 1024.0);
    }

    /** yyyymmdd → "Oct 27, 2026"; "" for 0. */
    public static String date(int yyyymmdd) {
        if (yyyymmdd <= 0)
            return "";
        int y = yyyymmdd / 10000;
        int m = (yyyymmdd / 100) % 100;
        int d = yyyymmdd % 100;
        if (m < 1 || m > 12)
            return "";
        return MONTHS[m - 1] + " " + d + ", " + y;
    }

    /**
     * Folder path for display: top-level parentheticals dropped and levels
     * joined with " › ", e.g. "ArcGIS Data for ATAK (KMZs)/Common Intelligence
     * Picture" → "ArcGIS Data for ATAK › Common Intelligence Picture".
     */
    public static String folder(String path) {
        if (path == null || path.isEmpty())
            return "";
        String[] segs = path.split("/");
        StringBuilder sb = new StringBuilder();
        for (String s : segs) {
            String t = s.replaceAll("\\s*\\([^)]*\\)\\s*$", "").trim();
            if (t.isEmpty())
                t = s;
            if (sb.length() > 0)
                sb.append(" › ");
            sb.append(t);
        }
        return sb.toString();
    }

    /** Last folder level, cleaned like {@link #folder}. */
    public static String folderName(String path) {
        String f = folder(path);
        int i = f.lastIndexOf(" › ");
        return i < 0 ? f : f.substring(i + 3);
    }

    /** File name for display: extension kept, underscores shown as spaces. */
    public static String fileName(String name) {
        return name.replace('_', ' ');
    }

    /** "Oct 27, 2026 · v2" style detail for a file name (no size). */
    public static String version(FileName n) {
        StringBuilder sb = new StringBuilder(date(n.getDate()));
        if (n.getVersion() >= 2) {
            if (sb.length() > 0)
                sb.append(" · ");
            sb.append("v").append(n.getVersion());
        }
        return sb.toString();
    }
}
