package com.atakmap.android.ghostmaps.plugin;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.GitBlobHasher;
import com.atakmap.android.ghostmaps.plugin.core.RepoFile;
import com.atakmap.android.ghostmaps.plugin.core.TreeParser;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Talks to GitHub: lists the GhostMaps repo with one call to the git tree
 * API (cached on disk, revalidated with an ETag so unchanged listings don't
 * count against GitHub's 60-requests-per-hour anonymous limit) and downloads
 * files from raw.githubusercontent.com, resuming interrupted downloads and
 * checking each file against the blob SHA in the listing.
 *
 * <p>All methods block; call them off the UI thread.</p>
 */
final class GitHubSource {

    static final String OWNER = "s2underground";
    static final String REPO = "GhostMaps";
    static final String BRANCH = "main";

    private static final String API_URL = "https://api.github.com/repos/"
            + OWNER + "/" + REPO + "/git/trees/" + BRANCH + "?recursive=1";
    private static final String RAW_URL = "https://raw.githubusercontent.com/"
            + OWNER + "/" + REPO + "/" + BRANCH + "/";
    private static final String USER_AGENT = "GhostMaps-ATAK-plugin";

    private static final int CONNECT_TIMEOUT_MS = 20_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** A repo listing and where it came from. */
    static final class Listing {
        final Catalog catalog;
        /** When GitHub last confirmed this listing (ms since epoch). */
        final long checkedAt;
        /** Null when fresh from GitHub; otherwise why the saved copy is shown. */
        final String notice;
        final boolean truncated;

        Listing(Catalog catalog, long checkedAt, String notice, boolean truncated) {
            this.catalog = catalog;
            this.checkedAt = checkedAt;
            this.notice = notice;
            this.truncated = truncated;
        }
    }

    interface Progress {
        void onProgress(long bytesDone, long bytesTotal);
    }

    /** Thrown when the user cancels a download. */
    static final class CancelledException extends IOException {
        CancelledException() {
            super("Cancelled");
        }
    }

    private final File treeFile;
    private final File metaFile;
    private final File downloadDir;

    GitHubSource(File dataDir) {
        treeFile = new File(dataDir, "tree.json");
        metaFile = new File(dataDir, "tree.properties");
        downloadDir = new File(dataDir, "downloads");
    }

    File getDownloadDir() {
        return downloadDir;
    }

    /** The listing saved from the last successful check, or null. */
    synchronized Listing cached() {
        if (!treeFile.isFile())
            return null;
        try {
            TreeParser.Result r = TreeParser.parse(readText(treeFile));
            long at = parseLong(loadMeta().getProperty("checkedAt"), treeFile.lastModified());
            return new Listing(new Catalog(r.files), at, null, r.truncated);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    /**
     * Asks GitHub for the current listing. Falls back to the saved listing
     * (with a notice) when offline or rate-limited; throws only when there's
     * nothing saved to fall back on.
     */
    synchronized Listing refresh() throws IOException {
        Properties meta = loadMeta();
        HttpURLConnection c = null;
        String why;
        try {
            c = open(API_URL);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            String etag = meta.getProperty("etag");
            if (etag != null && treeFile.isFile())
                c.setRequestProperty("If-None-Match", etag);

            int code = c.getResponseCode();
            long now = System.currentTimeMillis();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                meta.setProperty("checkedAt", Long.toString(now));
                saveMeta(meta);
                Listing l = cached();
                if (l != null)
                    return new Listing(l.catalog, now, null, l.truncated);
                // saved copy unreadable: drop the ETag so the next check
                // downloads the whole listing again
                meta.remove("etag");
                saveMeta(meta);
                return fallback("The saved file list was unreadable; tap refresh again");
            }
            if (code == HttpURLConnection.HTTP_OK) {
                String body = readText(c.getInputStream());
                TreeParser.Result r;
                try {
                    r = TreeParser.parse(body);
                } catch (JSONException e) {
                    throw new IOException("GitHub sent a listing GhostMaps can't read", e);
                }
                writeText(treeFile, body);
                String newTag = c.getHeaderField("ETag");
                if (newTag != null)
                    meta.setProperty("etag", newTag);
                else
                    meta.remove("etag");
                meta.setProperty("checkedAt", Long.toString(now));
                saveMeta(meta);
                return new Listing(new Catalog(r.files), now, null, r.truncated);
            }
            if ((code == 403 || code == 429)
                    && "0".equals(c.getHeaderField("X-RateLimit-Remaining"))) {
                long reset = parseLong(c.getHeaderField("X-RateLimit-Reset"), 0) * 1000L;
                why = "GitHub's hourly limit for this network was reached"
                        + (reset > 0 ? "; it resets at "
                                + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(reset))
                                : "");
            } else {
                why = "GitHub answered HTTP " + code;
            }
        } catch (NoListingException e) {
            throw e;
        } catch (IOException e) {
            why = "Couldn't reach GitHub (" + describe(e) + ")";
        } finally {
            if (c != null)
                c.disconnect();
        }
        return fallback(why);
    }

    /** No fresh listing and nothing saved to show instead. */
    static final class NoListingException extends IOException {
        NoListingException(String msg) {
            super(msg);
        }
    }

    private Listing fallback(String why) throws IOException {
        Listing l = cached();
        if (l == null)
            throw new NoListingException(why + ". Connect to the internet and refresh.");
        return new Listing(l.catalog, l.checkedAt, why, l.truncated);
    }

    /**
     * Downloads {@code f} to {@code dest} (replacing it). Resumes a partial
     * download left by an earlier attempt and verifies the result against the
     * blob SHA from the listing.
     */
    void download(RepoFile f, File dest, Progress progress, AtomicBoolean cancel)
            throws IOException {
        mkdirs(downloadDir);
        File part = new File(downloadDir, f.getSha() + ".part");
        long size = f.getSize();

        GitBlobHasher hasher = new GitBlobHasher(size);
        long have = part.isFile() ? part.length() : 0;
        if (have > 0 && have <= size)
            have = hashExisting(part, hasher);
        else if (have > size)
            have = restart(part);
        if (have != hasher.bytesHashed()) {
            hasher = new GitBlobHasher(size);
            have = restart(part);
        }

        if (have < size) {
            HttpURLConnection c = open(RAW_URL + encodePath(f.getPath()));
            try {
                // raw transfer: compression would break byte ranges
                c.setRequestProperty("Accept-Encoding", "identity");
                if (have > 0)
                    c.setRequestProperty("Range", "bytes=" + have + "-");
                int code = c.getResponseCode();
                boolean append = have > 0 && code == HttpURLConnection.HTTP_PARTIAL;
                if (code == 416) {
                    // stale partial file: start over next time
                    restart(part);
                    throw new IOException("Download couldn't resume; try again");
                }
                if (code != HttpURLConnection.HTTP_OK && !append) {
                    if (code == HttpURLConnection.HTTP_NOT_FOUND)
                        throw new IOException("The file is no longer on GitHub; refresh the list");
                    throw new IOException("GitHub answered HTTP " + code);
                }
                if (!append) {
                    hasher = new GitBlobHasher(size);
                    have = 0;
                }
                try (InputStream in = c.getInputStream();
                        OutputStream out = new FileOutputStream(part, append)) {
                    byte[] buf = new byte[64 * 1024];
                    long lastReport = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        if (cancel != null && cancel.get())
                            throw new CancelledException();
                        out.write(buf, 0, n);
                        hasher.update(buf, 0, n);
                        have += n;
                        if (have > size)
                            break;
                        long now = System.currentTimeMillis();
                        if (progress != null && now - lastReport > 250) {
                            lastReport = now;
                            progress.onProgress(have, size);
                        }
                    }
                }
            } finally {
                c.disconnect();
            }
        }
        if (progress != null)
            progress.onProgress(Math.min(have, size), size);

        if (!hasher.matches(f.getSha())) {
            restart(part);
            if (have < size)
                throw new IOException("Download stopped early; tap again to resume");
            throw new IOException("The download didn't match GitHub's copy (damaged in transit, or changed on GitHub meanwhile); refresh and try again");
        }
        if (dest.exists() && !dest.delete())
            throw new IOException("Cannot replace " + dest.getName());
        if (!part.renameTo(dest))
            throw new IOException("Cannot save " + dest.getName());
    }

    /** Deletes partial downloads nothing will resume (older than a week). */
    void cleanDownloads() {
        File[] files = downloadDir.listFiles();
        if (files == null)
            return;
        long cutoff = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        for (File f : files)
            if (f.lastModified() < cutoff)
                //noinspection ResultOfMethodCallIgnored
                f.delete();
    }

    // ------------------------------------------------------------------

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/")) {
            if (sb.length() > 0)
                sb.append('/');
            try {
                sb.append(URLEncoder.encode(seg, "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                throw new IllegalStateException(e);
            }
        }
        return sb.toString();
    }

    private static long hashExisting(File part, GitBlobHasher hasher) throws IOException {
        long total = 0;
        try (InputStream in = new FileInputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                hasher.update(buf, 0, n);
                total += n;
            }
        }
        return total;
    }

    private static long restart(File part) {
        //noinspection ResultOfMethodCallIgnored
        part.delete();
        return 0;
    }

    private Properties loadMeta() {
        Properties p = new Properties();
        if (metaFile.isFile()) {
            try (InputStream in = new FileInputStream(metaFile)) {
                p.load(in);
            } catch (IOException ignored) {
                // treat as empty
            }
        }
        return p;
    }

    private void saveMeta(Properties p) throws IOException {
        mkdirs(metaFile.getParentFile());
        try (OutputStream out = new FileOutputStream(metaFile)) {
            p.store(out, "GhostMaps listing cache");
        }
    }

    private static String readText(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return readText(in);
        }
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void writeText(File f, String text) throws IOException {
        mkdirs(f.getParentFile());
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f) && (!f.delete() || !tmp.renameTo(f)))
            throw new IOException("Cannot write " + f);
    }

    private static void mkdirs(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static String describe(IOException e) {
        if (e instanceof java.net.UnknownHostException)
            return "no internet connection";
        if (e instanceof java.net.SocketTimeoutException)
            return "connection timed out";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
