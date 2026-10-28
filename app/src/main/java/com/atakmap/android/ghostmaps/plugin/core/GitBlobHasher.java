package com.atakmap.android.ghostmaps.plugin.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Computes a file's git blob ID (SHA-1 of "blob &lt;size&gt;\0" + contents)
 * as it streams in, so a download can be checked against the SHA GitHub
 * listed for it. A mismatch means a damaged transfer or a file that changed
 * on GitHub mid-download.
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

    /** Lower-case hex digest; only meaningful once exactly {@code size} bytes were fed. */
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
}
