package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertNotNull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared test helpers. */
public final class Fixtures {

    /** Real repository listings captured from s2underground/GhostMaps. */
    public static final String MORNING_OCT_28 = "/ghostmaps-tree-2026-10-28.json";
    public static final String AFTERNOON_OCT_28 = "/ghostmaps-tree-2026-10-28-afternoon.json";
    public static final String MORNING_OCT_29 = "/ghostmaps-tree-2026-10-29.json";

    public static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    private Fixtures() {
    }

    public static String resource(String name) throws IOException {
        try (InputStream in = Fixtures.class.getResourceAsStream(name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    public static Catalog real(String name) throws Exception {
        return TreeParser.parse(resource(name), COMMIT).catalog;
    }

    /** A 40-hex SHA made from a short label, so tests stay readable. */
    public static String sha(String label) {
        StringBuilder sb = new StringBuilder();
        for (char ch : label.toCharArray())
            sb.append(String.format("%02x", (int) ch & 0xff));
        while (sb.length() < 40)
            sb.append('0');
        return sb.substring(0, 40);
    }

    public static RepoEntry e(String path, String label) {
        return new RepoEntry(path, sha(label), 1000);
    }

    public static Catalog cat(RepoEntry... entries) {
        return new Catalog(COMMIT, Arrays.asList(entries));
    }

    public static InstallRecord managed(String path, String label, File local) {
        return new InstallRecord(InstallStore.newId(), path, sha(label), 1000,
                FileKind.fromFileName(path), local.getAbsolutePath(), null,
                InstallRecord.Origin.GHOSTMAPS, InstallRecord.State.ACTIVE, 1, 1, null);
    }

    public static InstallRecord legacy(String path, String label, File local) {
        return new InstallRecord(InstallStore.newId(), path, sha(label), 1000,
                FileKind.fromFileName(path), local.getAbsolutePath(), null,
                InstallRecord.Origin.LEGACY, InstallRecord.State.ACTIVE, 1, 1, null);
    }

    /** Writes a ZIP with the given entry names (contents are the names). */
    public static File zip(File f, List<String> names) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            for (String n : names) {
                z.putNextEntry(new ZipEntry(n));
                if (!n.endsWith("/"))
                    z.write(n.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return f;
    }

    public static File zipWithXml(File f, String xml, String... images) throws IOException {
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            z.putNextEntry(new ZipEntry("iconset.xml"));
            z.write(xml.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            for (String n : images) {
                z.putNextEntry(new ZipEntry(n));
                z.write(new byte[] { 1, 2, 3 });
                z.closeEntry();
            }
        }
        return f;
    }

    /** Fixed or manually advanced time. */
    public static final class FakeClock implements Clock {
        private volatile long now;

        public FakeClock(long start) {
            now = start;
        }

        @Override
        public long now() {
            return now;
        }

        public void advance(long ms) {
            now += ms;
        }
    }
}
