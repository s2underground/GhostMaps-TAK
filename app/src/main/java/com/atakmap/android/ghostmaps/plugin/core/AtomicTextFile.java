package com.atakmap.android.ghostmaps.plugin.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * A small text file written so that a crash or power loss never leaves it
 * half-written or missing: the new text goes to {@code name.tmp} and is
 * synced, then renamed over the main file. The previous version is kept as
 * {@code name.bak}, so a damaged main file can be recovered from the last
 * good version, and reading falls back to it when the main file is missing.
 * (Like Android's AtomicFile, without the Android dependency.)
 */
public final class AtomicTextFile {

    private final File file;
    private final File backup;
    private final File temp;

    public AtomicTextFile(File file) {
        this.file = file;
        this.backup = new File(file.getPath() + ".bak");
        this.temp = new File(file.getPath() + ".tmp");
    }

    /** True if there's something to read (main file or a backup). */
    public boolean exists() {
        return file.isFile() || backup.isFile();
    }

    /** Contents, preferring the main file; null when neither file exists. */
    public String read() throws IOException {
        if (file.isFile())
            return readAll(file);
        if (backup.isFile())
            return readAll(backup);
        return null;
    }

    /** The backup's contents, or null; used to recover from a corrupt main file. */
    public String readBackup() throws IOException {
        return backup.isFile() ? readAll(backup) : null;
    }

    public void write(String text) throws IOException {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (file.isFile()) {
            if (backup.exists() && !backup.delete())
                throw new IOException("Cannot replace " + backup);
            if (!file.renameTo(backup))
                throw new IOException("Cannot back up " + file);
        }
        if (!temp.renameTo(file)) {
            // put the old version back so readers still find it
            if (backup.isFile() && !file.exists())
                //noinspection ResultOfMethodCallIgnored
                backup.renameTo(file);
            throw new IOException("Cannot write " + file);
        }
        // the backup stays: it is the last good version before this write
    }

    /** Moves an unreadable file aside (kept for inspection) so it is never overwritten. */
    public File quarantine(long now) {
        File q = new File(file.getPath() + ".corrupt-" + now);
        return file.isFile() && file.renameTo(q) ? q : null;
    }

    static String readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
