package com.atakmap.android.ghostmaps.plugin.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Decides whether a downloaded ZIP is an ATAK icon set before anything is
 * handed to ATAK. ATAK accepts a ZIP of images, with or without an
 * {@code iconset.xml} (it generates one, naming the set after the file and
 * using the ZIP's SHA-256 as its UID). Anything else in the ZIP (a data or
 * mission package, documents) means it is not an icon set, and GhostMaps
 * won't install it.
 */
public final class IconSetInspector {

    private static final Pattern UID_ATTR = Pattern.compile(
            "<iconset\\b[^>]*\\buid\\s*=\\s*\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);

    public static final class Result {
        public final boolean valid;
        /** Why not, for the user; null when valid. */
        public final String reason;
        public final int iconCount;
        /** UID declared in iconset.xml, or null (ATAK then uses the ZIP's SHA-256). */
        public final String declaredUid;

        Result(boolean valid, String reason, int iconCount, String declaredUid) {
            this.valid = valid;
            this.reason = reason;
            this.iconCount = iconCount;
            this.declaredUid = declaredUid;
        }
    }

    private IconSetInspector() {
    }

    public static Result inspect(File zip) {
        int icons = 0;
        String uid = null;
        try (ZipFile z = new ZipFile(zip)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                String base = name.substring(name.lastIndexOf('/') + 1);
                String lower = base.toLowerCase(Locale.US);
                if (e.isDirectory() || name.startsWith("__MACOSX/") || lower.startsWith(".")
                        || lower.equals("thumbs.db") || lower.equals("desktop.ini"))
                    continue;
                if (name.contains("..") || name.startsWith("/"))
                    return new Result(false, "the ZIP contains unsafe paths", icons, null);
                if (lower.equals("iconset.xml")) {
                    if (name.equalsIgnoreCase("iconset.xml"))
                        uid = readUid(z, e);
                    continue;
                }
                if (isImage(lower)) {
                    icons++;
                    continue;
                }
                return new Result(false, "it contains \"" + base
                        + "\", so it isn't an icon set (data packages and other ZIPs aren't supported)",
                        icons, null);
            }
        } catch (IOException e) {
            return new Result(false, "the ZIP couldn't be read (" + e.getMessage() + ")", 0, null);
        }
        if (icons == 0)
            return new Result(false, "it contains no images", 0, null);
        return new Result(true, null, icons, uid);
    }

    static boolean isImage(String lowerName) {
        return lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".gif")
                || lowerName.endsWith(".bmp");
    }

    private static String readUid(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 65536)
                out.write(buf, 0, n);
            Matcher m = UID_ATTR.matcher(new String(out.toByteArray(), StandardCharsets.UTF_8));
            return m.find() ? m.group(1).trim() : null;
        }
    }
}
