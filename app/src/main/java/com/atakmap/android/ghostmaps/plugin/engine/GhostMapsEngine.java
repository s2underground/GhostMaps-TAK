package com.atakmap.android.ghostmaps.plugin.engine;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.Clock;
import com.atakmap.android.ghostmaps.plugin.core.DeviceState;
import com.atakmap.android.ghostmaps.plugin.core.FileKind;
import com.atakmap.android.ghostmaps.plugin.core.GitBlobHasher;
import com.atakmap.android.ghostmaps.plugin.core.IconSetInspector;
import com.atakmap.android.ghostmaps.plugin.core.InstallRecord;
import com.atakmap.android.ghostmaps.plugin.core.InstallStore;
import com.atakmap.android.ghostmaps.plugin.core.LocalNames;
import com.atakmap.android.ghostmaps.plugin.core.MapLayerInspector;
import com.atakmap.android.ghostmaps.plugin.core.OtherSources;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.core.UpdatePlanner;
import com.atakmap.android.ghostmaps.plugin.net.CancelToken;
import com.atakmap.android.ghostmaps.plugin.net.RepoClient;
import com.atakmap.android.ghostmaps.plugin.net.RepoSource;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The GhostMaps engine: owns the install records, the download/install
 * queue and reconciliation with what ATAK actually has loaded. Plain Java;
 * ATAK is reached only through {@link Platform}.
 *
 * <h3>Threading</h3>
 * All state lives on one single-threaded <em>state</em> executor; public
 * methods only post work to it. Downloads and listing refreshes run on the
 * <em>network</em> executor and post their results back. Listeners receive
 * immutable {@link Snapshot}s on the <em>UI</em> executor. The engine owns
 * the state and network executors and shuts them down in {@link #dispose()}.
 *
 * <h3>Install pipeline (one at a time)</h3>
 * QUEUED → DOWNLOADING (verified, commit-pinned) → INSTALLING (validate,
 * record PLACED, move into GhostMaps' folder, record IMPORT_REQUESTED, ask
 * ATAK) → WAITING (until ATAK lists it, or a size-based timeout) → done.
 * Older versions are removed only once the new one is ACTIVE.
 *
 * <h3>Reconciliation</h3>
 * Compares records with the device on start, on panel open, after ATAK
 * file-list events and after each job. It finishes interrupted installs and
 * removals, re-points files that moved in the repository, drops records
 * whose file the user removed, re-adopts GhostMaps files by content hash and
 * (only after start-up has settled and the panel is in use) asks ATAK once
 * per session to reload a layer it has lost. It never deletes anything
 * outside GhostMaps' folder and never acts on a device state it couldn't read.
 */
public final class GhostMapsEngine {

    public interface Listener {
        void onSnapshot(Snapshot snapshot);

        /**
         * One call per finished install or update, on the UI executor.
         * {@code error} is null on success; {@code stillLoading} is true when
         * ATAK hadn't confirmed the file yet when waiting stopped.
         */
        void onInstallFinished(RepoEntry target, String error, boolean stillLoading);
    }

    /** One download-and-install. Fields are state-thread only unless noted. */
    private static final class Job {
        final RepoEntry target;
        final String commitSha;
        final Set<String> replaces = new LinkedHashSet<>();
        final boolean userInitiated;
        final CancelToken cancel = new CancelToken(); // thread-safe
        volatile long bytes; // written by the network thread
        Snapshot.JobPhase phase = Snapshot.JobPhase.QUEUED;
        String error;
        String recordId;
        long deadline;

        Job(RepoEntry target, String commitSha, Set<String> replaces, boolean userInitiated) {
            this.target = target;
            this.commitSha = commitSha;
            this.replaces.addAll(replaces);
            this.userInitiated = userInitiated;
        }
    }

    private final Platform platform;
    private final RepoSource repo;
    private final File storeFile;
    private final File installDir;
    private final File downloadDir;
    private final ScheduledExecutorService state;
    private final ExecutorService net;
    private final Executor ui;
    private final Clock clock;
    private final EngineLog log;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean disposed;
    private volatile long lastProgressPublish;
    /** Jobs as of the last publish, so dispose() can cancel downloads from any thread. */
    private volatile List<Job> jobsForDispose = Collections.emptyList();
    private static final long DISPOSE_WAIT_MS = 1000;

    // ---- state thread only ----
    private InstallStore store;
    private RepoSource.Listing listing;
    private boolean refreshing;
    private String listingError;
    private DeviceState device;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private Job current;
    private final Map<String, Long> missingSince = new HashMap<>();
    private final Map<String, Long> removeIssued = new HashMap<>();
    private final Map<String, Long> autoReloaded = new HashMap<>();
    private final Map<String, String> orphanShas = new HashMap<>();
    private long startedAt;
    private boolean panelUsed;
    private boolean reconcilePending;
    private boolean pollPending;

    public GhostMapsEngine(Platform platform, RepoSource repo, File storeFile, File installDir,
            File downloadDir, ScheduledExecutorService stateExecutor, ExecutorService netExecutor,
            Executor uiExecutor, Clock clock, EngineLog log) {
        this.platform = platform;
        this.repo = repo;
        this.storeFile = storeFile;
        this.installDir = installDir;
        this.downloadDir = downloadDir;
        this.state = stateExecutor;
        this.net = netExecutor;
        this.ui = uiExecutor;
        this.clock = clock;
        this.log = log;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    // ================================================================ public API
    // (any thread; work is posted to the state thread)

    /** Loads saved state and checks the device. Call once. */
    public void start() {
        post(new Runnable() {
            @Override
            public void run() {
                startedAt = clock.now();
                store = new InstallStore(storeFile, installDir, clock);
                if (store.recoveredFromCorruption())
                    log.w("installed.json was unreadable; it was set aside and files will be re-adopted", null);
                listing = repo.cached();
                reconcile();
            }
        });
    }

    /** The panel was opened: refresh the listing if stale and check the device. */
    public void panelOpened() {
        post(new Runnable() {
            @Override
            public void run() {
                panelUsed = true;
                reconcile();
                refreshListing(false);
            }
        });
    }

    /** Re-reads the listing now. */
    public void refresh() {
        post(new Runnable() {
            @Override
            public void run() {
                refreshListing(true);
            }
        });
    }

    /**
     * Installs a repository file. Replaces only an earlier install of the
     * very same file (same path); other versions stay until the user updates.
     */
    public void install(final RepoEntry entry) {
        post(new Runnable() {
            @Override
            public void run() {
                Set<String> replaces = new LinkedHashSet<>();
                for (InstallRecord r : store.all())
                    if (r.isManaged() && r.repoPath().equals(entry.path())
                            && !r.sha().equals(entry.sha()))
                        replaces.add(r.id());
                enqueue(entry, replaces, true);
            }
        });
    }

    /**
     * Installs {@code target} as an update: once it is loaded, it replaces
     * every installed record whose update it is (and any earlier copy of the
     * same path). This is exactly the file the user confirmed.
     */
    public void updateTo(final RepoEntry target) {
        post(new Runnable() {
            @Override
            public void run() {
                Catalog c = catalog();
                if (c == null)
                    return;
                Set<String> shas = managedShas();
                shas.remove(target.sha()); // plan as if the target weren't installed yet
                Set<String> replaces = new LinkedHashSet<>();
                for (InstallRecord r : store.all()) {
                    if (!r.isManaged() || r.sha().equals(target.sha()))
                        continue;
                    UpdatePlanner.Status s = UpdatePlanner.evaluate(r, c, shas);
                    if (r.repoPath().equals(target.path())
                            || (s.hasUpdate() && s.target.path().equals(target.path())))
                        replaces.add(r.id());
                }
                enqueue(target, replaces, true);
            }
        });
    }

    /** Installs every pending update; each replaces exactly the records it was planned for. */
    public void updateAll() {
        post(new Runnable() {
            @Override
            public void run() {
                Catalog c = catalog();
                if (c == null)
                    return;
                Map<String, List<String>> plan = UpdatePlanner.updatePlan(
                        UpdatePlanner.evaluateAll(store.all(), c));
                for (Map.Entry<String, List<String>> e : plan.entrySet()) {
                    RepoEntry target = c.find(e.getKey());
                    if (target != null)
                        enqueue(target, new LinkedHashSet<>(e.getValue()), true);
                }
            }
        });
    }

    /** User confirmed that {@code replacement} supersedes the record (a "similar file" suggestion). */
    public void replaceWith(final String recordId, final RepoEntry replacement) {
        post(new Runnable() {
            @Override
            public void run() {
                if (store.get(recordId) != null)
                    enqueue(replacement, Collections.singleton(recordId), true);
            }
        });
    }

    /** Asks ATAK to load an installed file again. */
    public void reload(final String recordId) {
        post(new Runnable() {
            @Override
            public void run() {
                InstallRecord r = store.get(recordId);
                if (r == null || !r.isManaged() || !fileOf(r).exists())
                    return;
                requestImport(r, true);
                reconcileLater(Tuning.confirmTimeoutMs(r.kind(), r.size()));
                publish();
            }
        });
    }

    /** Takes an installed file out of ATAK and deletes it (GhostMaps' own files only). */
    public void remove(final String recordId) {
        post(new Runnable() {
            @Override
            public void run() {
                removeRecord(recordId);
                publish();
            }
        });
    }

    /** Drops an unmanaged (early test build) record; its file is left alone. */
    public void forget(final String recordId) {
        post(new Runnable() {
            @Override
            public void run() {
                InstallRecord r = store.get(recordId);
                if (r != null && !r.isManaged()) {
                    forgetRecord(r);
                    publish();
                }
            }
        });
    }

    /** Stops a queued or downloading job, or clears a failed one. */
    public void cancel(final String targetPath) {
        post(new Runnable() {
            @Override
            public void run() {
                Job j = jobs.get(targetPath);
                if (j == null)
                    return;
                switch (j.phase) {
                    case QUEUED:
                    case FAILED:
                        queue.remove(j);
                        jobs.remove(targetPath);
                        publish();
                        break;
                    case DOWNLOADING:
                        j.cancel.cancel(); // the download thread reports back
                        break;
                    default:
                        break; // installing: too late to stop safely
                }
            }
        });
    }

    /** ATAK's file list or icon sets changed; check again shortly. */
    public void deviceChanged() {
        post(new Runnable() {
            @Override
            public void run() {
                if (reconcilePending)
                    return;
                reconcilePending = true;
                schedule(new Runnable() {
                    @Override
                    public void run() {
                        reconcilePending = false;
                        reconcile();
                        checkWaiting();
                    }
                }, Tuning.EVENT_DEBOUNCE_MS);
            }
        });
    }

    /**
     * ATAK reported a new icon set. If GhostMaps is waiting for an icon set
     * and the UID it expected hasn't appeared, ATAK chose a different UID
     * (e.g. it rejected the ZIP's iconset.xml); record the one ATAK used.
     */
    public void iconSetAdded(final String uid) {
        post(new Runnable() {
            @Override
            public void run() {
                Job j = current;
                if (uid != null && j != null && j.phase == Snapshot.JobPhase.WAITING
                        && j.target.kind() == FileKind.ICON_SET) {
                    final InstallRecord r = store.get(j.recordId);
                    DeviceState d = probe();
                    if (r != null && d != null && !uid.equals(r.iconSetUid())
                            && recordWithIconSet(uid) == null && !d.hasIconSet(r.iconSetUid())
                            && uid.equals(fallbackIconSetUid(r))) {
                        // ATAK ignored the ZIP's iconset.xml UID and used its own (the
                        // file's SHA-256); any other new UID is someone else's icon set
                        log.i("ATAK installed the icon set under UID " + uid);
                        quietly(new IoAction() {
                            @Override
                            public void run() throws IOException {
                                store.put(r.withIconSetUid(uid));
                            }
                        });
                    }
                }
                checkWaiting();
            }
        });
        deviceChanged();
    }

    /** A text dump of the engine's state, for troubleshooting; delivered on the UI executor. */
    public void dumpState(final Callback<String> cb) {
        post(new Runnable() {
            @Override
            public void run() {
                final String text = describeState();
                ui.execute(new Runnable() {
                    @Override
                    public void run() {
                        cb.accept(text);
                    }
                });
            }
        });
    }

    private String fallbackIconSetUid(InstallRecord r) {
        try {
            return platform.iconSetUid(fileOf(r), null);
        } catch (IOException e) {
            return null;
        }
    }

    public interface Callback<T> {
        void accept(T value);
    }

    /** Stops everything; no callbacks or writes happen afterwards. */
    public void dispose() {
        disposed = true;
        listeners.clear();
        // cancel downloads promptly (thread-safe); state is abandoned with its executor
        for (Job j : jobsForDispose)
            j.cancel.cancel();
        state.shutdownNow();
        net.shutdownNow();
        try {
            // let a task already running finish its write before another engine may start
            state.awaitTermination(DISPOSE_WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ================================================================= listing

    private Catalog catalog() {
        return listing == null ? null : listing.catalog;
    }

    private void refreshListing(boolean force) {
        if (refreshing)
            return;
        if (!force && listing != null && listing.notice == null
                && clock.now() - listing.checkedAt < Tuning.LISTING_STALE_MS)
            return;
        refreshing = true;
        publish();
        runOnNet(new Runnable() {
            @Override
            public void run() {
                RepoSource.Listing l = null;
                String err = null;
                try {
                    l = repo.refresh();
                } catch (IOException e) {
                    err = e.getMessage();
                } catch (RuntimeException e) {
                    log.w("listing refresh failed", e);
                    err = "Couldn't read the file list (" + e + ")";
                }
                final RepoSource.Listing got = l;
                final String error = err;
                post(new Runnable() {
                    @Override
                    public void run() {
                        refreshing = false;
                        if (got != null) {
                            listing = got;
                            listingError = null;
                        } else {
                            listingError = error;
                        }
                        reconcile();
                    }
                });
            }
        });
    }

    // ================================================================ pipeline

    private void enqueue(RepoEntry requested, Set<String> replaces, boolean user) {
        Catalog c = catalog();
        if (c == null || requested.kind() == null)
            return;
        // always download what the current list has at that path, pinned to its
        // commit (the caller's entry may come from an older list or a failed job)
        RepoEntry target = c.find(requested.path());
        if (target == null) {
            notifyFinished(requested, "No longer on GitHub; refresh the list", false);
            publish();
            return;
        }
        final Job existing = jobs.get(target.path());
        if (existing != null && existing.phase != Snapshot.JobPhase.FAILED) {
            existing.replaces.addAll(replaces);
            final InstallRecord placed = existing.recordId == null ? null : store.get(existing.recordId);
            if (placed != null) // already recorded: the record carries the replace list
                quietly(new IoAction() {
                    @Override
                    public void run() throws IOException {
                        store.put(placed.withReplaces(new ArrayList<>(existing.replaces)));
                    }
                });
            publish();
            return;
        }
        Set<String> all = new LinkedHashSet<>(replaces);
        if (existing != null)
            all.addAll(existing.replaces); // retrying a failed job keeps its plan
        Job j = new Job(target, c.commitSha(), all, user);
        jobs.put(target.path(), j);
        queue.add(j);
        publish();
        pump();
    }

    private void pump() {
        if (current != null || disposed)
            return;
        Job j = queue.poll();
        if (j == null)
            return;
        current = j;
        j.phase = Snapshot.JobPhase.DOWNLOADING;
        publish();
        final Job job = j;
        final File tmp = new File(downloadDir, j.target.sha() + ".download");
        runOnNet(new Runnable() {
            @Override
            public void run() {
                try {
                    repo.download(job.target, job.commitSha, tmp, new RepoSource.Progress() {
                        @Override
                        public void onProgress(long done, long total) {
                            job.bytes = done;
                            long now = clock.now();
                            if (now - lastProgressPublish >= Tuning.PROGRESS_PUBLISH_MS) {
                                lastProgressPublish = now;
                                post(new Runnable() {
                                    @Override
                                    public void run() {
                                        publish();
                                    }
                                });
                            }
                        }
                    }, job.cancel);
                    post(new Runnable() {
                        @Override
                        public void run() {
                            onDownloaded(job, tmp);
                        }
                    });
                } catch (RepoSource.CancelledException e) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            endJob(job, null, false, true);
                        }
                    });
                } catch (IOException | RuntimeException e) {
                    final String msg = e instanceof IOException
                            ? RepoClient.describe((IOException) e)
                            : "Unexpected error (" + e + ")";
                    if (e instanceof RuntimeException)
                        log.w("download crashed", e);
                    post(new Runnable() {
                        @Override
                        public void run() {
                            fail(job, msg);
                        }
                    });
                }
            }
        });
    }

    private void onDownloaded(Job j, File tmp) {
        if (j.cancel.isCancelled()) {
            delete(tmp);
            endJob(j, null, false, true);
            return;
        }
        try {
            installDownloaded(j, tmp);
        } catch (IOException e) {
            delete(tmp);
            fail(j, RepoClient.describe(e));
        }
    }

    private void installDownloaded(Job j, File tmp) throws IOException {
        j.phase = Snapshot.JobPhase.INSTALLING;
        publish();
        RepoEntry t = j.target;

        String uid = null;
        if (t.kind() == FileKind.MAP_LAYER) {
            String problem = MapLayerInspector.problem(tmp, t.fileName());
            if (problem != null) {
                delete(tmp);
                fail(j, "Not a map layer: " + problem);
                return;
            }
        } else {
            IconSetInspector.Result ins = IconSetInspector.inspect(tmp);
            if (!ins.valid) {
                delete(tmp);
                fail(j, "Not an icon set: " + ins.reason);
                return;
            }
            uid = platform.iconSetUid(tmp, ins.declaredUid);
            DeviceState d = probe();
            if (d == null) { // can't tell whether the set is someone else's: don't guess
                delete(tmp);
                fail(j, "Couldn't read ATAK's icon sets; try again");
                return;
            }
            InstallRecord mine = recordWithIconSet(uid);
            if (d.hasIconSet(uid) && mine == null) {
                delete(tmp);
                fail(j, "ATAK already has this icon set from another source; GhostMaps left it alone");
                return;
            }
            if (mine != null && mine.sha().equals(t.sha())) {
                delete(tmp);
                endJob(j, null, false, false); // already installed
                return;
            }
            if (mine != null && !j.replaces.contains(mine.id())) {
                delete(tmp);
                fail(j, "This icon set has the same ID as the installed \"" + fileOf(mine).getName()
                        + "\"; remove that one first");
                return;
            }
            // a new version of one of GhostMaps' own sets (same UID): ATAK replaces it in
            // place, and the old record is retired once the new one is in (activate)
        }

        mkdirs(installDir);
        File dest = new File(installDir, LocalNames.unique(t.fileName(), takenNames()));
        long now = clock.now();
        InstallRecord rec = new InstallRecord(InstallStore.newId(), t.path(), t.sha(), t.size(),
                t.kind(), dest.getAbsolutePath(), uid, InstallRecord.Origin.GHOSTMAPS,
                InstallRecord.State.PLACED, now, now, new ArrayList<>(j.replaces));
        store.put(rec); // write-ahead: a crash after this is finished by reconcile
        try {
            move(tmp, dest);
        } catch (IOException e) {
            quietly(new IoAction() {
                @Override
                public void run() throws IOException {
                    store.remove(rec.id());
                }
            });
            throw e;
        }
        j.recordId = rec.id();
        if (!requestImport(rec, j.userInitiated)) {
            // the file is in place and recorded as PLACED; reconcile retries the import
            fail(j, "Couldn't save GhostMaps' install list (storage full or read-only?)");
            return;
        }
        j.phase = Snapshot.JobPhase.WAITING;
        j.deadline = clock.now() + Tuning.confirmTimeoutMs(t.kind(), t.size());
        schedulePoll();
        publish();
    }

    /** One pending confirmation check at a time, however many events arrive. */
    private void schedulePoll() {
        if (pollPending)
            return;
        pollPending = true;
        schedule(new Runnable() {
            @Override
            public void run() {
                pollPending = false;
                checkWaiting();
            }
        }, Tuning.CONFIRM_POLL_MS);
    }

    /** Polls the current job's record until ATAK confirms it or time runs out. */
    private void checkWaiting() {
        Job j = current;
        if (j == null || j.phase != Snapshot.JobPhase.WAITING)
            return;
        InstallRecord rec = store.get(j.recordId);
        if (rec == null) {
            fail(j, "The install was interrupted; try again");
            return;
        }
        if (!isPending(rec)) { // e.g. removed meanwhile: the job is over, replace nothing
            endJob(j, null, false, true);
            return;
        }
        DeviceState d = probe();
        if (d != null && isActive(rec, d)) {
            activate(rec);
            endJob(j, null, false, false);
        } else if (clock.now() >= j.deadline) {
            endJob(j, null, true, false); // record stays IMPORT_REQUESTED; reconcile finishes it
        } else {
            schedulePoll();
        }
    }

    /** Records IMPORT_REQUESTED, then asks ATAK; false (nothing asked) if the record couldn't be saved. */
    private boolean requestImport(InstallRecord rec, boolean notifyUser) {
        final InstallRecord r = rec.withState(InstallRecord.State.IMPORT_REQUESTED, clock.now());
        if (!quietly(new IoAction() {
            @Override
            public void run() throws IOException {
                store.put(r);
            }
        }))
            return false;
        File f = fileOf(r);
        if (r.kind() == FileKind.MAP_LAYER)
            platform.importMapLayer(f, notifyUser);
        else
            platform.installIconSet(f);
        return true;
    }

    /** Marks a record ACTIVE, then removes the records it replaces. */
    private void activate(InstallRecord rec) {
        if (!isPending(rec))
            return;
        final InstallRecord active = rec.withState(InstallRecord.State.ACTIVE, clock.now());
        if (!quietly(new IoAction() {
            @Override
            public void run() throws IOException {
                store.put(active);
            }
        }))
            return;
        for (String id : active.replaces()) {
            InstallRecord old = store.get(id);
            if (old == null || id.equals(active.id()))
                continue;
            if (old.kind() == FileKind.ICON_SET && old.iconSetUid() != null
                    && old.iconSetUid().equals(active.iconSetUid())) {
                // ATAK replaced this set in place: removing the UID would remove the new
                // version, so only GhostMaps' old copy of the ZIP goes
                deleteOwned(fileOf(old));
                forgetRecord(old);
            } else {
                removeRecord(id);
            }
        }
        if (!active.replaces().isEmpty())
            quietly(new IoAction() {
                @Override
                public void run() throws IOException {
                    store.put(active.withReplaces(null));
                }
            });
    }

    private void endJob(Job j, String error, boolean stillLoading, boolean cancelled) {
        jobs.remove(j.target.path());
        queue.remove(j);
        if (current == j)
            current = null;
        if (!cancelled)
            notifyFinished(j.target, error, stillLoading);
        publish();
        pump();
    }

    private void fail(Job j, String msg) {
        j.phase = Snapshot.JobPhase.FAILED;
        j.error = msg;
        if (current == j)
            current = null;
        notifyFinished(j.target, msg, false);
        publish();
        pump();
    }

    // ================================================================= removal

    private void removeRecord(String id) {
        InstallRecord r = store.get(id);
        if (r == null)
            return;
        if (current != null && id.equals(current.recordId)) {
            log.i("not removing " + r + " while it is being installed");
            return;
        }
        File f = fileOf(r);
        if (!r.isManaged() || !InstallStore.isInside(f, installDir)) {
            forgetRecord(r); // never touch files GhostMaps doesn't own
            return;
        }
        final InstallRecord removing = r.withState(InstallRecord.State.REMOVING, clock.now());
        quietly(new IoAction() {
            @Override
            public void run() throws IOException {
                store.put(removing);
            }
        });
        removeIssued.put(id, clock.now());
        DeviceState d = probe();
        if (r.kind() == FileKind.MAP_LAYER) {
            if (d != null && d.isLoaded(platform.canonical(f)))
                platform.removeMapLayer(f); // ATAK unloads it and deletes the file
            else
                deleteOwned(f);
        } else {
            if (r.iconSetUid() != null && d != null && d.hasIconSet(r.iconSetUid()))
                platform.removeIconSet(r.iconSetUid());
            deleteOwned(f);
        }
        reconcileLater(Tuning.EVENT_DEBOUNCE_MS * 2);
    }

    private void forgetRecord(final InstallRecord r) {
        quietly(new IoAction() {
            @Override
            public void run() throws IOException {
                store.remove(r.id());
            }
        });
        missingSince.remove(r.id());
        removeIssued.remove(r.id());
        autoReloaded.remove(r.id());
    }

    // ============================================================ reconciliation

    private void reconcile() {
        if (store == null)
            return;
        DeviceState d = probe();
        if (d == null) { // can't see ATAK's state right now: decide nothing
            publish();
            return;
        }
        long now = clock.now();
        Catalog c = catalog();
        if (c != null) {
            for (Map.Entry<String, String> e : UpdatePlanner.relinks(store.all(), c).entrySet()) {
                final InstallRecord r = store.get(e.getKey());
                final String path = e.getValue();
                quietly(new IoAction() {
                    @Override
                    public void run() throws IOException {
                        store.put(r.withRepoPath(path));
                    }
                });
            }
        }

        boolean again = false;
        Set<String> stillMissing = new HashSet<>();
        String busy = current != null ? current.recordId : null;
        for (InstallRecord r : store.all()) {
            if (r.id().equals(busy))
                continue;
            File f = fileOf(r);
            boolean exists = f.exists();
            if (!r.isManaged()) {
                if (d.storageAvailable() && !exists)
                    forgetRecord(r); // gone; nothing to protect
                continue;
            }
            boolean active = isActive(r, d);
            switch (r.state()) {
                case PLACED: // crashed between placing and asking ATAK
                    if (exists) {
                        requestImport(r, false);
                        again = true; // confirm it (or retry) without waiting for an ATAK event
                    } else {
                        again |= missing(r, d, now, stillMissing, false);
                    }
                    break;
                case IMPORT_REQUESTED:
                    if (active)
                        activate(r);
                    else if (!exists)
                        again |= missing(r, d, now, stillMissing, false);
                    break;
                case ACTIVE:
                    if (active)
                        break;
                    if (r.kind() == FileKind.MAP_LAYER) {
                        if (!exists)
                            again |= missing(r, d, now, stillMissing, false);
                        else if (autoReloadAllowed(now) && !autoReloaded.containsKey(r.id())) {
                            autoReloaded.put(r.id(), now);
                            log.i("asking ATAK to reload " + f);
                            platform.importMapLayer(f, false);
                            again = true;
                        }
                    } else {
                        again |= reconcileIconSet(r, d, exists, now, stillMissing);
                    }
                    break;
                case REMOVING:
                    again |= finishRemoval(r, d, now);
                    break;
            }
        }
        missingSince.keySet().retainAll(stillMissing);
        if (c != null)
            adoptOrphans(c, d);
        if (again)
            reconcileLater(Tuning.MISSING_GRACE_MS);
        publish();
    }

    /**
     * Counts a record as missing from the device; after the grace period
     * drops the record (and, if asked, deletes GhostMaps' own copy of the file).
     */
    private boolean missing(InstallRecord r, DeviceState d, long now, Set<String> stillMissing,
            boolean deleteFile) {
        if (!d.storageAvailable())
            return true; // can't judge while storage is unavailable
        stillMissing.add(r.id());
        Long since = missingSince.get(r.id());
        if (since == null) {
            missingSince.put(r.id(), now);
            return true;
        }
        if (now - since >= Tuning.MISSING_GRACE_MS) {
            log.i("removed outside GhostMaps; forgetting " + r);
            if (deleteFile)
                deleteOwned(fileOf(r));
            forgetRecord(r);
            return false;
        }
        return true;
    }

    private boolean reconcileIconSet(final InstallRecord r, DeviceState d, boolean exists, long now,
            Set<String> stillMissing) {
        if (r.iconSetUid() == null) {
            // record from an earlier build: learn the UID ATAK would use
            if (!exists) {
                forgetRecord(r);
                return false;
            }
            try {
                IconSetInspector.Result ins = IconSetInspector.inspect(fileOf(r));
                final String uid = platform.iconSetUid(fileOf(r), ins.declaredUid);
                quietly(new IoAction() {
                    @Override
                    public void run() throws IOException {
                        store.put(r.withIconSetUid(uid));
                    }
                });
                return true;
            } catch (IOException e) {
                log.w("couldn't read icon set " + r.localPath(), e);
                return false;
            }
        }
        // the icon set isn't in ATAK (removed in ATAK's icon manager): after the
        // grace period, forget it and delete GhostMaps' copy of the ZIP
        return missing(r, d, now, stillMissing, true);
    }

    private boolean finishRemoval(InstallRecord r, DeviceState d, long now) {
        File f = fileOf(r);
        boolean stillThere = r.kind() == FileKind.MAP_LAYER
                ? d.isLoaded(platform.canonical(f))
                : d.hasIconSet(r.iconSetUid());
        if (!stillThere) {
            deleteOwned(f);
            forgetRecord(r);
            return false;
        }
        Long issued = removeIssued.get(r.id());
        if (issued == null || now - issued >= Tuning.REMOVE_GRACE_MS) {
            removeIssued.put(r.id(), now);
            if (r.kind() == FileKind.MAP_LAYER)
                platform.removeMapLayer(f);
            else
                platform.removeIconSet(r.iconSetUid());
        }
        return true;
    }

    /**
     * Files in GhostMaps' folder with no record (e.g. the store was lost)
     * whose contents match a published file get a record again.
     */
    private void adoptOrphans(Catalog c, DeviceState d) {
        File[] files = installDir.listFiles();
        if (files == null)
            return;
        Set<String> known = new HashSet<>();
        for (InstallRecord r : store.all())
            known.add(new File(r.localPath()).getAbsolutePath());
        Set<String> seen = new HashSet<>();
        for (File f : files) {
            String name = f.getName();
            if (!f.isFile() || name.startsWith(".") || known.contains(f.getAbsolutePath())
                    || FileKind.fromFileName(name) == null)
                continue;
            String key = f.getAbsolutePath() + '|' + f.length() + '|' + f.lastModified();
            seen.add(key);
            String sha = orphanShas.get(key);
            if (sha == null) {
                try {
                    sha = GitBlobHasher.of(f);
                } catch (IOException e) {
                    continue;
                }
                orphanShas.put(key, sha);
            }
            RepoEntry match = null;
            for (RepoEntry e : c.findBySha(sha))
                if (e.kind() == FileKind.fromFileName(name)) {
                    match = e;
                    break;
                }
            if (match == null)
                continue; // not identifiable as published data: never ours to touch
            adopt(f, match, d);
        }
        orphanShas.keySet().retainAll(seen);
    }

    private void adopt(File f, RepoEntry e, DeviceState d) {
        String uid = null;
        InstallRecord.State st = InstallRecord.State.ACTIVE;
        if (e.kind() == FileKind.ICON_SET) {
            IconSetInspector.Result ins = IconSetInspector.inspect(f);
            if (!ins.valid)
                return;
            try {
                uid = platform.iconSetUid(f, ins.declaredUid);
            } catch (IOException ex) {
                return;
            }
            if (!d.hasIconSet(uid))
                st = InstallRecord.State.PLACED;
        }
        long now = clock.now();
        final InstallRecord r = new InstallRecord(InstallStore.newId(), e.path(), e.sha(), e.size(),
                e.kind(), f.getAbsolutePath(), uid, InstallRecord.Origin.GHOSTMAPS, st, now, now, null);
        log.i("re-adopting " + f);
        quietly(new IoAction() {
            @Override
            public void run() throws IOException {
                store.put(r);
            }
        });
    }

    private boolean autoReloadAllowed(long now) {
        return panelUsed && now - startedAt >= Tuning.STARTUP_QUIET_MS;
    }

    // ================================================================ snapshot

    private void publish() {
        if (disposed || store == null)
            return;
        long now = clock.now();
        Catalog c = catalog();
        List<InstallRecord> all = store.all();
        Set<String> shas = managedShas();
        List<Snapshot.RecordView> views = new ArrayList<>(all.size());
        List<UpdatePlanner.Status> statuses = new ArrayList<>();
        for (InstallRecord r : all) {
            UpdatePlanner.Status s = c == null ? null : UpdatePlanner.evaluate(r, c, shas);
            if (s != null)
                statuses.add(s);
            views.add(new Snapshot.RecordView(r, presence(r, now), s));
        }
        Map<String, Snapshot.JobView> jv = new LinkedHashMap<>();
        for (Job j : jobs.values()) {
            int pct = j.target.size() <= 0 ? 0
                    : (int) Math.min(100, j.bytes * 100 / j.target.size());
            jv.put(j.target.path(), new Snapshot.JobView(j.target, j.phase, pct, j.error,
                    !j.replaces.isEmpty()));
        }
        jobsForDispose = new ArrayList<>(jobs.values());
        final Snapshot snap = new Snapshot(listing, refreshing, listingError, views, jv,
                otherSources(), UpdatePlanner.countUpdates(statuses));
        ui.execute(new Runnable() {
            @Override
            public void run() {
                if (disposed)
                    return;
                for (Listener l : listeners)
                    l.onSnapshot(snap);
            }
        });
    }

    private Snapshot.Presence presence(InstallRecord r, long now) {
        if (!r.isManaged())
            return Snapshot.Presence.UNMANAGED;
        if (r.state() == InstallRecord.State.REMOVING)
            return Snapshot.Presence.REMOVING;
        DeviceState d = device;
        if (d != null && isActive(r, d))
            return Snapshot.Presence.ACTIVE;
        boolean waiting = current != null && r.id().equals(current.recordId);
        long timeout = Tuning.confirmTimeoutMs(r.kind(), r.size());
        Long reload = autoReloaded.get(r.id());
        if (waiting || d == null
                || (r.state() != InstallRecord.State.ACTIVE && now - r.stateSince() < timeout)
                || (reload != null && now - reload < timeout))
            return Snapshot.Presence.LOADING;
        return Snapshot.Presence.NOT_LOADED;
    }

    private OtherSources otherSources() {
        DeviceState d = device;
        if (d == null)
            return OtherSources.NONE;
        String own = platform.canonical(installDir) + File.separator;
        List<String> others = new ArrayList<>();
        for (String p : d.loadedFiles())
            if (!p.startsWith(own))
                others.add(p);
        return new OtherSources(others);
    }

    private void notifyFinished(final RepoEntry target, final String error, final boolean still) {
        ui.execute(new Runnable() {
            @Override
            public void run() {
                if (disposed)
                    return;
                for (Listener l : listeners)
                    l.onInstallFinished(target, error, still);
            }
        });
    }

    private String describeState() {
        StringBuilder sb = new StringBuilder();
        Catalog c = catalog();
        sb.append("listing: ").append(c == null ? "none" : c.size() + " files @ " + c.commitSha())
                .append(listing != null && listing.notice != null ? " (" + listing.notice + ")" : "")
                .append(listingError != null ? " error=" + listingError : "").append('\n');
        DeviceState d = probe();
        sb.append("device: ").append(d == null ? "unreadable"
                : d.loadedFiles().size() + " loaded files, storage "
                        + (d.storageAvailable() ? "ok" : "UNAVAILABLE")).append('\n');
        sb.append("records:\n");
        long now = clock.now();
        if (store != null)
            for (InstallRecord r : store.all())
                sb.append("  ").append(presence(r, now)).append(' ').append(r.state()).append(' ')
                        .append(r.kind()).append(' ').append(r.repoPath()).append("\n      -> ")
                        .append(r.localPath()).append(fileOf(r).exists() ? "" : " (FILE MISSING)")
                        .append(r.iconSetUid() != null ? " uid=" + r.iconSetUid() : "")
                        .append('\n');
        sb.append("jobs:\n");
        for (Job j : jobs.values())
            sb.append("  ").append(j.phase).append(' ').append(j.target.path())
                    .append(j.error != null ? " error=" + j.error : "").append('\n');
        return sb.toString();
    }

    // ================================================================= helpers

    /**
     * Reads ATAK's state. Every successful read also becomes what the panel
     * shows, so a just-confirmed install never displays as "not loaded".
     */
    private DeviceState probe() {
        try {
            DeviceState d = platform.probe();
            if (d != null)
                device = d;
            return d;
        } catch (RuntimeException e) {
            log.w("couldn't read ATAK's state", e);
            return null;
        }
    }

    private boolean isActive(InstallRecord r, DeviceState d) {
        if (r.kind() == FileKind.MAP_LAYER)
            return d.isLoaded(platform.canonical(fileOf(r)));
        return d.hasIconSet(r.iconSetUid());
    }

    /** Placed or requested, i.e. an install that may still be confirmed. */
    private static boolean isPending(InstallRecord r) {
        return r.state() == InstallRecord.State.PLACED
                || r.state() == InstallRecord.State.IMPORT_REQUESTED;
    }

    private InstallRecord recordWithIconSet(String uid) {
        for (InstallRecord r : store.all())
            if (r.isManaged() && uid.equals(r.iconSetUid()))
                return r;
        return null;
    }

    private Set<String> managedShas() {
        Set<String> s = new HashSet<>();
        if (store != null)
            for (InstallRecord r : store.all())
                if (r.isManaged())
                    s.add(r.sha());
        return s;
    }

    private Set<String> takenNames() {
        Set<String> taken = new HashSet<>();
        String[] present = installDir.list();
        if (present != null)
            for (String n : present)
                taken.add(n.toLowerCase(Locale.US));
        for (InstallRecord r : store.all())
            taken.add(new File(r.localPath()).getName().toLowerCase(Locale.US));
        return taken;
    }

    private static File fileOf(InstallRecord r) {
        return new File(r.localPath());
    }

    /** Deletes a file only if it is inside GhostMaps' folder. */
    private void deleteOwned(File f) {
        if (f.exists() && InstallStore.isInside(f, installDir) && !f.delete())
            log.w("couldn't delete " + f, null);
    }

    private static void delete(File f) {
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static void mkdirs(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
    }

    private static void move(File from, File to) throws IOException {
        if (from.renameTo(to))
            return;
        try (InputStream in = new FileInputStream(from);
                OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        } catch (IOException e) {
            delete(to);
            throw e;
        }
        delete(from);
    }

    private interface IoAction {
        void run() throws IOException;
    }

    /** Runs a store write; logs and returns false on failure (the next change retries). */
    private boolean quietly(IoAction a) {
        try {
            a.run();
            return true;
        } catch (IOException e) {
            log.w("couldn't save the install list", e);
            return false;
        }
    }

    private void reconcileLater(long delayMs) {
        schedule(new Runnable() {
            @Override
            public void run() {
                reconcile();
            }
        }, delayMs);
    }

    private Runnable guarded(final Runnable r) {
        return new Runnable() {
            @Override
            public void run() {
                if (disposed)
                    return;
                try {
                    r.run();
                } catch (RuntimeException e) {
                    log.w("GhostMaps engine error", e);
                }
            }
        };
    }

    private void post(Runnable r) {
        if (disposed)
            return;
        try {
            state.execute(guarded(r));
        } catch (RejectedExecutionException ignored) {
            // disposed concurrently
        }
    }

    private void schedule(Runnable r, long delayMs) {
        if (disposed)
            return;
        try {
            state.schedule(guarded(r), delayMs, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) {
            // disposed concurrently
        }
    }

    private void runOnNet(Runnable r) {
        if (disposed)
            return;
        try {
            net.execute(r);
        } catch (RejectedExecutionException ignored) {
            // disposed concurrently
        }
    }
}
