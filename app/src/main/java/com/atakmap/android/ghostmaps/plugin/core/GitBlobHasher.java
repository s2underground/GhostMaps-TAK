package com.atakmap.android.ghostmaps.plugin.core;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Computes a file's git blob ID (SHA-1 of "blob &lt;size&gt;\0" + contents),
 * the same value GitHub lists for each file. Used to verify downloads and to
 * recognize files on the device by content.
 *
 * <p>SHA-1 is required here: it is how git names file contents, so it is the
 * only checksum GitHub's file list provides. It detects corrupted or wrong
 * downloads; authenticity comes from fetching the list and files over HTTPS
 * from GitHub.</p>
 */
public final class GitBlobHasher {

    private final MessageDigest sha1;
    private final long expectedSize;
    private long fed;

    public GitBlobHasher(long size) {
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        expectedSize = size;
        sha1.update(("blob " + size + "\0").getBytes(StandardCharsets.US_ASCII));
    }

    public void update(byte[] buf, int off, int len) {
        sha1.update(buf, off, len);
        fed += len;
    }

    public long bytesHashed() {
        return fed;
    }

    /** Lower-case hex digest. Call once, after exactly {@code size} bytes. */
    public String hex() {
        byte[] d = sha1.digest();
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d)
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        return sb.toString();
    }

    public boolean matches(String expectedSha) {
        return fed == expectedSize && hex().equalsIgnoreCase(expectedSha);
    }

    /** Git blob SHA-1 of some bytes. */
    public static String of(byte[] data) {
        GitBlobHasher h = new GitBlobHasher(data.length);
        h.update(data, 0, data.length);
        return h.hex();
    }

    /** Git blob SHA-1 of a whole file. */
    public static String of(File f) throws IOException {
        GitBlobHasher h = new GitBlobHasher(f.length());
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0)
                h.update(buf, 0, n);
        }
        return h.hex();
    }
}
