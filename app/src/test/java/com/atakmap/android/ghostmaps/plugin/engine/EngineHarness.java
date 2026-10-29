package com.atakmap.android.ghostmaps.plugin.engine;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.DeviceState;
import com.atakmap.android.ghostmaps.plugin.core.Fixtures;
import com.atakmap.android.ghostmaps.plugin.core.IconSetInspector;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.net.CancelToken;
import com.atakmap.android.ghostmaps.plugin.net.RepoSource;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the real engine deterministically: a virtual-time state executor,
 * a network executor drained on demand, a fake ATAK and a fake repository.
 */
final class EngineHarness {

    final Fixtures.FakeClock clock = new Fixtures.FakeClock(1_700_000_000_000L);
    final ManualScheduler state = new ManualScheduler(clock);
    final ManualScheduler net = new ManualScheduler(clock);
    final FakePlatform platform = new FakePlatform();
    final FakeRepo repo = new FakeRepo();
    final TestLog log = new TestLog();
    final File root;
    final File installDir;
    final File storeFile;
    final File downloadDir;
    final List<Snapshot> snapshots = new ArrayList<>();
    final List<String> finished = new ArrayList<>();
    GhostMapsEngine engine;

    EngineHarness(File root) {
        this.root = root;
        installDir = new File(root, "atak/GhostMaps");
        storeFile = new File(root, "atak/tools/ghostmaps/installed.json");
        downloadDir = new File(root, "atak/tools/ghostmaps/downloads");
    }

    GhostMapsEngine create() {
        engine = new GhostMapsEngine(platform, repo, storeFile, installDir, downloadDir, state, net,
                Runnable::run, clock, log);
        engine.addListener(new GhostMapsEngine.Listener() {
            @Override
            public void onSnapshot(Snapshot s) {
                snapshots.add(s);
            }

            @Override
            public void onInstallFinished(RepoEntry target, String error, boolean stillLoading) {
                finished.add(target.fileName() + (error != null ? " ERROR " + error : "")
                        + (stillLoading ? " STILL-LOADING" : ""));
            }
        });
        return engine;
    }

    GhostMapsEngine start() {
        create().start();
        settle();
        return engine;
    }

    /** Runs everything that is due now, on both executors, until quiet. */
    void settle() {
        boolean did;
        do {
            did = state.runDue();
            did |= net.runDue();
        } while (did);
    }

    /** Moves virtual time forward, running whatever becomes due on the way. */
    void advance(long ms) {
        long target = clock.now() + ms;
        settle();
        Long next;
        while ((next = state.nextDue()) != null && next <= target) {
            if (next > clock.now())
                clock.advance(next - clock.now());
            settle();
        }
        if (target > clock.now())
            clock.advance(target - clock.now());
        settle();
    }

    Snapshot last() {
        return snapshots.get(snapshots.size() - 1);
    }

    Snapshot.RecordView only() {
        if (last().records.size() != 1)
            throw new AssertionError("expected one record, got " + last().records.size());
        return last().records.get(0);
    }

    Snapshot.RecordView recordAt(String repoPath) {
        for (Snapshot.RecordView v : last().records)
            if (v.record.repoPath().equals(repoPath))
                return v;
        return null;
    }

    void publish(RepoEntry... entries) {
        repo.publish(clock.now(), entries);
    }

    /** An entry whose SHA really is the git blob SHA of {@code data}. */
    RepoEntry entry(String path, byte[] data) {
        RepoEntry e = new RepoEntry(path, blobSha(data), data.length);
        repo.content.put(e.sha(), data);
        return e;
    }

    /** A real (tiny) KMZ whose contents depend on {@code label}. */
    RepoEntry kmz(String path) {
        return kmz(path, path);
    }

    RepoEntry kmz(String path, String label) {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(bytes)) {
            z.putNextEntry(new java.util.zip.ZipEntry("doc.kml"));
            z.write(("<kml><!-- " + label + " --></kml>").getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        return entry(path, bytes.toByteArray());
    }

    RepoEntry iconZip(String path, String... names) throws IOException {
        File tmp = File.createTempFile("icons", ".zip", root);
        Fixtures.zip(tmp, Arrays.asList(names));
        return entry(path, java.nio.file.Files.readAllBytes(tmp.toPath()));
    }

    static String blobSha(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(("blob " + data.length + "\0").getBytes(StandardCharsets.US_ASCII));
            md.update(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest())
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static String canonical(File f) {
        try {
            return f.getCanonicalPath();
        } catch (IOException e) {
            return f.getAbsolutePath();
        }
    }

    // ------------------------------------------------------------------ fakes

    /** ATAK, as the engine sees it. */
    static final class FakePlatform implements Platform {
        final Set<String> loaded = new LinkedHashSet<>();
        final Set<String> iconSets = new LinkedHashSet<>();
        boolean storageAvailable = true;
        boolean unreadable;
        /** ATAK loads a requested layer at once. */
        boolean autoLoad = true;
        boolean autoAddIcons = true;
        /** ATAK ignores this many remove requests. */
        int ignoreRemoves;
        final List<String> calls = new ArrayList<>();

        @Override
        public DeviceState probe() {
            return unreadable ? null : new DeviceState(loaded, iconSets, storageAvailable);
        }

        @Override
        public void importMapLayer(File file, boolean notifyUser) {
            calls.add("import " + file.getName());
            if (autoLoad && file.exists())
                loaded.add(canonical(file));
        }

        @Override
        public void removeMapLayer(File file) {
            calls.add("remove " + file.getName());
            if (ignoreRemoves > 0) {
                ignoreRemoves--;
                return;
            }
            if (loaded.remove(canonical(file)))
                file.delete(); // ATAK deletes the file it unloads
        }

        @Override
        public void installIconSet(File zip) {
            calls.add("addIcons " + zip.getName());
            if (autoAddIcons) {
                try {
                    iconSets.add(iconSetUid(zip, IconSetInspector.inspect(zip).declaredUid));
                } catch (IOException e) {
                    throw new AssertionError(e);
                }
            }
        }

        @Override
        public void removeIconSet(String uid) {
            calls.add("removeIcons " + uid);
            iconSets.remove(uid);
        }

        @Override
        public String iconSetUid(File zip, String declaredUid) throws IOException {
            if (declaredUid != null)
                return declaredUid;
            return "sha-" + blobSha(java.nio.file.Files.readAllBytes(zip.toPath()));
        }

        @Override
        public String canonical(File file) {
            return EngineHarness.canonical(file);
        }

        int count(String prefix) {
            int n = 0;
            for (String c : calls)
                if (c.startsWith(prefix))
                    n++;
            return n;
        }
    }

    /** The repository: a listing and file bodies by SHA. */
    static final class FakeRepo implements RepoSource {
        final Map<String, byte[]> content = new HashMap<>();
        Listing listing;
        int failNextDownloads;
        int downloads;
        int refreshes;

        void publish(long now, RepoEntry... entries) {
            listing = new Listing(new Catalog(Fixtures.COMMIT, Arrays.asList(entries)), now, null, false);
        }

        @Override
        public Listing cached() {
            return listing;
        }

        @Override
        public Listing refresh() throws IOException {
            refreshes++;
            if (listing == null)
                throw new NoListingException("offline");
            return listing;
        }

        @Override
        public void download(RepoEntry entry, String commitSha, File dest, Progress progress,
                CancelToken cancel) throws IOException {
            if (cancel.isCancelled())
                throw new CancelledException();
            if (failNextDownloads > 0) {
                failNextDownloads--;
                throw new IOException("Network down");
            }
            byte[] data = content.get(entry.sha());
            if (data == null)
                throw new IOException("no such file");
            downloads++;
            dest.getParentFile().mkdirs();
            try (OutputStream out = new FileOutputStream(dest)) {
                out.write(data);
            }
        }
    }

    static final class TestLog implements EngineLog {
        final List<String> warnings = new ArrayList<>();
        final List<Throwable> crashes = new ArrayList<>();

        @Override
        public void i(String msg) {
        }

        @Override
        public void w(String msg, Throwable t) {
            warnings.add(msg);
            if (t instanceof RuntimeException || t instanceof Error)
                crashes.add(t);
        }
    }

    /** A single-threaded executor with virtual time, run only when the test says so. */
    static final class ManualScheduler extends AbstractExecutorService
            implements ScheduledExecutorService {

        private static final class Task implements Comparable<Task> {
            final long at;
            final long seq;
            final Runnable r;

            Task(long at, long seq, Runnable r) {
                this.at = at;
                this.seq = seq;
                this.r = r;
            }

            @Override
            public int compareTo(Task o) {
                return at != o.at ? Long.compare(at, o.at) : Long.compare(seq, o.seq);
            }
        }

        private final Fixtures.FakeClock clock;
        private final PriorityQueue<Task> tasks = new PriorityQueue<>();
        private long seq;
        private boolean shutdown;

        ManualScheduler(Fixtures.FakeClock clock) {
            this.clock = clock;
        }

        boolean runDue() {
            boolean did = false;
            Task t;
            while ((t = tasks.peek()) != null && t.at <= clock.now()) {
                tasks.poll();
                t.r.run();
                did = true;
            }
            return did;
        }

        Long nextDue() {
            Task t = tasks.peek();
            return t == null ? null : t.at;
        }

        /** Takes the queued work out (to run later, e.g. "in flight" across a dispose). */
        List<Runnable> drain() {
            List<Runnable> out = new ArrayList<>();
            Task t;
            while ((t = tasks.poll()) != null)
                out.add(t.r);
            return out;
        }

        @Override
        public void execute(Runnable command) {
            add(command, 0);
        }

        private void add(Runnable r, long delayMs) {
            if (shutdown)
                throw new RejectedExecutionException();
            tasks.add(new Task(clock.now() + Math.max(0, delayMs), seq++, r));
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            add(command, unit.toMillis(delay));
            return null; // the engine never uses the future
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable c, long i, long p, TimeUnit u) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable c, long i, long d, TimeUnit u) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return drain();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown && tasks.isEmpty();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return isTerminated();
        }
    }
}
