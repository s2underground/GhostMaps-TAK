package com.atakmap.android.ghostmaps.plugin.net;

import com.atakmap.android.ghostmaps.plugin.core.AtomicTextFile;
import com.atakmap.android.ghostmaps.plugin.core.Clock;
import com.atakmap.android.ghostmaps.plugin.core.Formats;
import com.atakmap.android.ghostmaps.plugin.core.GitBlobHasher;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.core.TreeParser;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * GitHub implementation of {@link RepoSource}.
 *
 * <h3>Listing (2 requests when the repo changed, 1 conditional request otherwise)</h3>
 * <ol>
 * <li>{@code GET /repos/{o}/{r}/commits/{branch}} with
 * {@code Accept: application/vnd.github.sha} and {@code If-None-Match}: the
 * branch's current commit SHA. A 304 doesn't count against GitHub's 60
 * requests/hour anonymous limit.</li>
 * <li>Only if the commit changed: {@code GET /repos/{o}/{r}/git/trees/{commit}?recursive=1}.</li>
 * </ol>
 * If the branch no longer exists, the repository's default branch is looked
 * up; the switch is saved only after a listing from it succeeds.
 *
 * <h3>Downloads</h3>
 * From {@code raw.githubusercontent.com/{o}/{r}/{commit}/{path}}: pinned to
 * the listed commit, so the bytes always match the listed SHA even while
 * the owner pushes changes. Downloads resume from {@code <sha>.part}, are
 * retried on transient failures with backoff, check free space first and are
 * verified against the git blob SHA before being handed over.
 *
 * <p>Thread-safe: listing calls are serialized; downloads only touch their
 * own {@code .part} file. No Android dependencies, so it is JVM-testable.</p>
 */
public final class RepoClient implements RepoSource {

    public static final String GITHUB_API = "https://api.github.com";
    public static final String GITHUB_RAW = "https://raw.githubusercontent.com";

    static final int CONNECT_TIMEOUT_MS = 20_000;
    static final int READ_TIMEOUT_MS = 30_000;
    static final int ATTEMPTS = 3;
    /** Extra free space required beyond the file itself. */
    static final long SPACE_MARGIN = 10L * 1024 * 1024;
    /** Partial downloads untouched this long are deleted. */
    static final long STALE_PART_MS = 7L * 24 * 3600 * 1000;

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final String USER_AGENT = "GhostMaps-ATAK-plugin";

    /** Waits between retries; replaceable in tests. */
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private final String api;
    private final String raw;
    /** The only places this client connects to: the API and raw-file origins it was built with. */
    private final Set<String> allowedOrigins = new HashSet<>();
    private final String owner;
    private final String repo;
    private final String preferredBranch;
    private final AtomicTextFile treeFile;
    private final AtomicTextFile metaFile;
    private final File downloadDir;
    private final Clock clock;
    private final Sleeper sleeper;
    private final Random random = new Random();

    public RepoClient(String apiBase, String rawBase, String owner, String repo,
            String preferredBranch, File dataDir, Clock clock, Sleeper sleeper) {
        this.api = apiBase;
        this.raw = rawBase;
        try {
            allowedOrigins.add(origin(new URL(apiBase)));
            allowedOrigins.add(origin(new URL(rawBase)));
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("bad base URL", e);
        }
        this.owner = owner;
        this.repo = repo;
        this.preferredBranch = preferredBranch;
        this.treeFile = new AtomicTextFile(new File(dataDir, "tree.json"));
        this.metaFile = new AtomicTextFile(new File(dataDir, "listing.properties"));
        this.downloadDir = new File(dataDir, "downloads");
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public static Sleeper realSleeper() {
        return new Sleeper() {
            @Override
            public void sleep(long ms) throws InterruptedException {
                Thread.sleep(ms);
            }
        };
    }

    public File downloadDir() {
        return downloadDir;
    }

    // ================================================================ listing

    @Override
    public synchronized Listing cached() {
        try {
            String json = treeFile.read();
            if (json == null)
                return null;
            Properties meta = loadMeta();
            String commit = meta.getProperty("commit", "");
            if (!SHA.matcher(commit).matches())
                return null; // saved by an older build without a commit: fetch afresh
            TreeParser.Result r = TreeParser.parse(json, commit);
            long at = parseLong(meta.getProperty("checkedAt"), 0);
            return new Listing(r.catalog, at, null, r.truncated);
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    @Override
    public synchronized Listing refresh() throws IOException {
        Properties meta = loadMeta();
        String branch = meta.getProperty("branch", preferredBranch);
        Listing saved = cached();

        Head head = null;
        if (!branch.equals(preferredBranch)) {
            // on a fallback branch: go back to the preferred one as soon as it exists again
            Head preferred = fetchHead(preferredBranch, null);
            if (preferred.sha != null) {
                branch = preferredBranch;
                head = preferred;
            }
        }
        if (head == null)
            head = fetchHead(branch, saved != null ? meta.getProperty("etag") : null);
        if (head.notModified && saved != null) {
            meta.setProperty("checkedAt", Long.toString(clock.now()));
            saveMeta(meta);
            return new Listing(saved.catalog, clock.now(), null, saved.truncated);
        }
        if (head.sha == null && head.branchMissing) {
            String def = fetchDefaultBranch();
            if (def != null && !def.equals(branch)) {
                Head h2 = fetchHead(def, null);
                if (h2.sha != null) {
                    branch = def;
                    head = h2;
                }
            }
        }
        if (head.sha == null)
            return fallback(saved, head.problem != null ? head.problem
                    : "The GhostMaps repository or its branch wasn't found on GitHub");

        if (saved != null && head.sha.equals(meta.getProperty("commit"))) {
            putHead(meta, branch, head);
            saveMeta(meta);
            return new Listing(saved.catalog, clock.now(), null, saved.truncated);
        }

        Response tree = get(api + "/repos/" + seg(owner) + "/" + seg(repo) + "/git/trees/"
                + head.sha + "?recursive=1", "application/vnd.github+json", null);
        if (tree.problem != null)
            return fallback(saved, tree.problem);
        if (tree.code != HttpURLConnection.HTTP_OK)
            return fallback(saved, describeStatus(tree));
        TreeParser.Result parsed;
        try {
            parsed = TreeParser.parse(tree.body, head.sha);
        } catch (JSONException e) {
            return fallback(saved, "GitHub sent a file list GhostMaps couldn't read");
        }
        treeFile.write(tree.body);
        putHead(meta, branch, head);
        saveMeta(meta);
        return new Listing(parsed.catalog, clock.now(), null, parsed.truncated);
    }

    private void putHead(Properties meta, String branch, Head head) {
        meta.setProperty("branch", branch);
        meta.setProperty("commit", head.sha);
        meta.setProperty("checkedAt", Long.toString(clock.now()));
        if (head.etag != null)
            meta.setProperty("etag", head.etag);
        else
            meta.remove("etag");
    }

    private Listing fallback(Listing saved, String why) throws NoListingException {
        if (saved == null)
            throw new NoListingException(why + ". Connect to the internet and refresh.");
        return saved.withNotice(why);
    }

    /** Result of asking for the branch's head commit. */
    private static final class Head {
        String sha;
        String etag;
        boolean notModified;
        boolean branchMissing;
        String problem;
    }

    private Head fetchHead(String branch, String etag) {
        Head h = new Head();
        Response r = get(api + "/repos/" + seg(owner) + "/" + seg(repo) + "/commits/" + seg(branch),
                "application/vnd.github.sha", etag);
        if (r.problem != null) {
            h.problem = r.problem;
        } else if (r.code == HttpURLConnection.HTTP_NOT_MODIFIED) {
            h.notModified = true;
        } else if (r.code == HttpURLConnection.HTTP_OK) {
            String sha = r.body.trim().toLowerCase(Locale.US);
            if (SHA.matcher(sha).matches()) {
                h.sha = sha;
                h.etag = r.etag;
            } else {
                h.problem = "GitHub sent an unexpected answer";
            }
        } else if (r.code == 404 || r.code == 409 || r.code == 422) {
            h.branchMissing = true;
            h.problem = describeStatus(r);
        } else {
            h.problem = describeStatus(r);
        }
        return h;
    }

    private String fetchDefaultBranch() {
        Response r = get(api + "/repos/" + seg(owner) + "/" + seg(repo),
                "application/vnd.github+json", null);
        if (r.problem != null || r.code != HttpURLConnection.HTTP_OK)
            return null;
        try {
            String b = new JSONObject(r.body).optString("default_branch", "");
            return b.isEmpty() ? null : b;
        } catch (JSONException e) {
            return null;
        }
    }

    /** A small API response, or why there is none. */
    private static final class Response {
        int code;
        String body = "";
        String etag;
        String rateRemaining;
        String rateReset;
        String retryAfter;
        /** Set when no HTTP answer was received at all. */
        String problem;
    }

    private Response get(String url, String accept, String etag) {
        Response r = new Response();
        HttpURLConnection c = null;
        try {
            c = open(url);
            c.setRequestProperty("Accept", accept);
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            if (etag != null)
                c.setRequestProperty("If-None-Match", etag);
            r.code = c.getResponseCode();
            requireAllowed(c.getURL()); // a redirect must not leave GitHub
            r.etag = c.getHeaderField("ETag");
            r.rateRemaining = c.getHeaderField("X-RateLimit-Remaining");
            r.rateReset = c.getHeaderField("X-RateLimit-Reset");
            r.retryAfter = c.getHeaderField("Retry-After");
            if (r.code == HttpURLConnection.HTTP_OK) {
                try (InputStream in = c.getInputStream()) {
                    r.body = readText(in);
                }
            }
        } catch (IOException e) {
            r.problem = "Couldn't reach GitHub (" + describe(e) + ")";
        } finally {
            if (c != null)
                c.disconnect();
        }
        return r;
    }

    private String describeStatus(Response r) {
        if ((r.code == 403 || r.code == 429) && "0".equals(r.rateRemaining)) {
            long reset = parseLong(r.rateReset, 0) * 1000L;
            return "GitHub's hourly limit for this network was reached"
                    + (reset > 0 ? "; it resets at "
                            + DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(reset))
                            : "");
        }
        if ((r.code == 403 || r.code == 429) && r.retryAfter != null)
            return "GitHub asked GhostMaps to wait " + parseLong(r.retryAfter, 60)
                    + " seconds before checking again";
        return "GitHub answered HTTP " + r.code;
    }

    // ============================================================== downloads

    /** Local storage trouble while saving a download: not a network problem, so never retried. */
    private static final class StorageException extends IOException {
        private static final long serialVersionUID = 1L;

        StorageException(IOException cause) {
            super("Couldn't save the download (" + describe(cause) + ")", cause);
        }
    }

    /** Network trouble worth retrying (timeouts, resets, 5xx, 429, a broken resume). */
    private static final class TransientException extends IOException {
        private static final long serialVersionUID = 1L;

        TransientException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    @Override
    public void download(RepoEntry entry, String commitSha, File dest, Progress progress,
            CancelToken cancel) throws IOException {
        if (!SHA.matcher(commitSha == null ? "" : commitSha).matches())
            throw new IOException("The file list is incomplete; refresh and try again");
        mkdirs(downloadDir);
        File part = new File(downloadDir, entry.sha() + ".part");
        long have = part.isFile() ? part.length() : 0;
        long free = downloadDir.getUsableSpace();
        long need = Math.max(0, entry.size() - have) + SPACE_MARGIN;
        if (free > 0 && free < need)
            throw new IOException("Not enough free storage (needs " + Formats.size(need)
                    + ", " + Formats.size(free) + " free)");

        String url = raw + "/" + seg(owner) + "/" + seg(repo) + "/" + commitSha + "/"
                + encodePath(entry.path());
        for (int attempt = 1;; attempt++) {
            if (cancel.isCancelled())
                throw new CancelledException();
            try {
                fetchInto(url, entry, part, progress, cancel);
                break;
            } catch (TransientException e) {
                if (cancel.isCancelled())
                    throw new CancelledException();
                if (attempt >= ATTEMPTS)
                    throw new IOException(e.getMessage() + "; tap Retry to continue", e);
                try {
                    sleeper.sleep(backoffMs(attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new CancelledException();
                }
            }
        }

        if (!GitBlobHasher.of(part).equals(entry.sha())) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw new IOException("The download didn't match GitHub's copy; try again");
        }
        if (dest.exists() && !dest.delete())
            throw new IOException("Cannot replace " + dest.getName());
        if (!part.renameTo(dest))
            throw new IOException("Cannot save " + dest.getName());
    }

    long backoffMs(int attempt) {
        long base = attempt == 1 ? 1000 : 4000;
        return base + random.nextInt(1000);
    }

    /** Brings {@code part} up to the full size; throws TransientException for retryable trouble. */
    private void fetchInto(String url, RepoEntry entry, File part, Progress progress,
            CancelToken cancel) throws IOException {
        long size = entry.size();
        long have = part.isFile() ? part.length() : 0;
        if (have > size) {
            delete(part);
            have = 0;
        }
        if (have == size)
            return;

        HttpURLConnection c;
        try {
            c = open(url);
        } catch (IOException e) {
            throw new TransientException("Couldn't reach GitHub (" + describe(e) + ")", e);
        }
        cancel.attach(c);
        try {
            c.setRequestProperty("Accept-Encoding", "identity"); // byte ranges need raw bytes
            if (have > 0)
                c.setRequestProperty("Range", "bytes=" + have + "-");
            int code;
            try {
                code = c.getResponseCode();
            } catch (IOException e) {
                if (cancel.isCancelled())
                    throw new CancelledException();
                throw new TransientException("Couldn't reach GitHub (" + describe(e) + ")", e);
            }
            requireAllowed(c.getURL()); // a redirect must not leave GitHub
            boolean append;
            if (code == HttpURLConnection.HTTP_PARTIAL && have > 0) {
                String range = c.getHeaderField("Content-Range");
                if (range == null || !range.trim().startsWith("bytes " + have + "-")) {
                    delete(part);
                    throw new TransientException("Download couldn't resume", null);
                }
                append = true;
            } else if (code == HttpURLConnection.HTTP_OK) {
                append = false; // server ignored the range: start over
                have = 0;
            } else if (code == 416) {
                delete(part);
                throw new TransientException("Download couldn't resume", null);
            } else if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                throw new IOException("GitHub no longer has this file at the listed version; refresh the list");
            } else if (code >= 500 || code == 429) {
                throw new TransientException("GitHub answered HTTP " + code, null);
            } else {
                throw new IOException("GitHub answered HTTP " + code);
            }

            OutputStream out;
            try {
                out = new FileOutputStream(part, append);
            } catch (IOException e) {
                throw new StorageException(e);
            }
            try (InputStream in = c.getInputStream(); OutputStream o = out) {
                byte[] buf = new byte[64 * 1024];
                long lastReport = 0;
                while (true) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException e) {
                        if (cancel.isCancelled())
                            throw new CancelledException();
                        throw new TransientException("Connection lost (" + describe(e) + ")", e);
                    }
                    if (n <= 0)
                        break;
                    if (cancel.isCancelled())
                        throw new CancelledException();
                    if (have + n > size) {
                        delete(part);
                        throw new TransientException("GitHub sent more data than expected", null);
                    }
                    try {
                        o.write(buf, 0, n);
                    } catch (IOException e) {
                        throw new StorageException(e); // storage full or removed
                    }
                    have += n;
                    long now = clock.now();
                    if (progress != null && now - lastReport >= 250) {
                        lastReport = now;
                        progress.onProgress(have, size);
                    }
                }
            } catch (IOException e) {
                if (e instanceof CancelledException || e instanceof TransientException
                        || e instanceof StorageException)
                    throw e;
                if (cancel.isCancelled())
                    throw new CancelledException();
                throw new TransientException("Connection lost (" + describe(e) + ")", e); // e.g. close
            }
            if (progress != null)
                progress.onProgress(have, size);
            if (have < size)
                throw new TransientException("Download stopped early", null);
        } finally {
            cancel.detach();
            c.disconnect();
        }
    }

    /** Deletes stale partial downloads and leftovers. */
    public void cleanDownloads() {
        File[] files = downloadDir.listFiles();
        if (files == null)
            return;
        long cutoff = clock.now() - STALE_PART_MS;
        for (File f : files)
            if (!f.getName().endsWith(".part") || f.lastModified() < cutoff)
                delete(f);
    }

    // ================================================================= helpers

    /**
     * Opens a connection, but only to one of the two GitHub origins this
     * client was configured with. Paths come from GitHub's file list, so they
     * are percent-encoded and can never change the scheme, host or port.
     */
    private HttpURLConnection open(String url) throws IOException {
        URL u = new URL(url);
        requireAllowed(u);
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setRequestProperty("User-Agent", USER_AGENT);
        c.setInstanceFollowRedirects(true);
        c.setUseCaches(false);
        return c;
    }

    private void requireAllowed(URL u) throws IOException {
        if (!allowedOrigins.contains(origin(u)))
            throw new IOException("Refusing to connect outside GitHub (" + u.getHost() + ")");
    }

    private static String origin(URL u) {
        int port = u.getPort() != -1 ? u.getPort() : u.getDefaultPort();
        return u.getProtocol().toLowerCase(Locale.US) + "://"
                + u.getHost().toLowerCase(Locale.US) + ":" + port;
    }

    private static String seg(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Percent-encodes each path segment (spaces, commas, parentheses, apostrophes…). */
    static String encodePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (String s : path.split("/")) {
            if (sb.length() > 0)
                sb.append('/');
            sb.append(seg(s));
        }
        return sb.toString();
    }

    private Properties loadMeta() {
        Properties p = new Properties();
        try {
            String text = metaFile.read();
            if (text != null)
                p.load(new StringReader(text));
        } catch (IOException ignored) {
            // treat as empty: the next refresh rebuilds it
        }
        return p;
    }

    private void saveMeta(Properties p) throws IOException {
        StringWriter w = new StringWriter();
        p.store(w, "GhostMaps listing");
        metaFile.write(w.toString());
    }

    private static String readText(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
    }

    private static void delete(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Short user-facing reason for an I/O error. */
    public static String describe(IOException e) {
        if (e instanceof UnknownHostException)
            return "no internet connection";
        if (e instanceof SocketTimeoutException)
            return "connection timed out";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }
}
