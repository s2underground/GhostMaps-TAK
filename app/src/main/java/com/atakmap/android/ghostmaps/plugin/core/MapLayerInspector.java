package com.atakmap.android.ghostmaps.plugin.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A cheap sanity check that a downloaded map layer really is a KMZ or KML
 * before ATAK is asked to load it (e.g. not a Git LFS pointer or an HTML
 * error page that happened to match a listed checksum).
 */
public final class MapLayerInspector {

    private static final String LFS_POINTER = "version https://git-lfs";

    private MapLayerInspector() {
    }

    /** Null when {@code file} looks like the layer {@code fileName} names; otherwise why not. */
    public static String problem(File file, String fileName) {
        byte[] head = new byte[64];
        int n;
        try (InputStream in = new FileInputStream(file)) {
            n = in.read(head);
        } catch (IOException e) {
            return "couldn't read the file";
        }
        if (n <= 0)
            return "the file is empty";
        String text = new String(head, 0, n, StandardCharsets.ISO_8859_1);
        if (text.startsWith(LFS_POINTER))
            return "GitHub returned a Git LFS pointer instead of the file";
        String name = fileName.toLowerCase(Locale.US);
        if (name.endsWith(".kmz")) {
            boolean zip = n >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4;
            return zip ? null : "not a KMZ (ZIP) file";
        }
        if (name.endsWith(".kml")) {
            int i = 0;
            if (n >= 3 && (head[0] & 0xff) == 0xEF && (head[1] & 0xff) == 0xBB && (head[2] & 0xff) == 0xBF)
                i = 3; // UTF-8 byte order mark
            while (i < n && Character.isWhitespace((char) head[i]))
                i++;
            return i < n && head[i] == '<' ? null : "not a KML (XML) file";
        }
        return "not a map layer";
    }
}
