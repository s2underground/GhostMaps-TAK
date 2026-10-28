package com.atakmap.android.ghostmaps.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import com.atakmap.android.data.FileContentHandler;
import com.atakmap.android.data.URIContentHandler;
import com.atakmap.android.data.URIContentListener;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.InstalledFile;
import com.atakmap.android.ghostmaps.plugin.core.InstalledStore;
import com.atakmap.android.ghostmaps.plugin.core.LocalNames;
import com.atakmap.android.ghostmaps.plugin.core.OtherSources;
import com.atakmap.android.ghostmaps.plugin.core.RepoFile;
import com.atakmap.android.ghostmaps.plugin.core.UpdateCheck;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * State behind the pane: the repo listing, what's installed and the download
 * queue. Downloads run one at a time on a background thread (kind to slow
 * links); imports run on the UI thread as ATAK requires. Listeners are told
 * about every change on the UI thread.
 *
 * <p>Ownership rule: GhostMaps only adds, replaces or removes files it
 * downloaded into atak/GhostMaps and recorded in installed.json. Files from
 * DataSync or anywhere else are read (to point out duplicates), never
 * touched.</p>
 */
final class GhostMapsManager implements URIContentListener {

    private static final String TAG = "GhostMaps";

    /** Re-check GitHub on pane open if the listing is older than this. */
    static final long STALE_AFTER_MS = 10 * 60 * 1000L;

    /** Re-request import of an installed file ATAK no longer lists after this long. */
    private static final long HEAL_AFTER_MS = 2 * 60 * 1000L;

    interface Listener {
        void onStateChanged();
    }

    /** Told once per finished job (error is null on success), on the UI thread. */
    interface JobListener {
        void onJobDone(RepoFile file, String error, boolean stillLoading);
    }

    enum Phase {
        QUEUED, DOWNLOADING, IMPORTING, FAILED
    }

    /** One file being fetched and imported. */
    static final class Job {
        final RepoFile file;
        final boolean replaceOlder;
        final AtomicBoolean cancel = new AtomicBoolean();
        volatile Phase phase = Phase.QUEUED;
        volatile long bytesDone;
        volatile String error;

        Job(RepoFile file, boolean replaceOlder) {
            this.file = file;
            this.replaceOlder = replaceOlder;
        }

        int percent() {
            long total = file.getSize();
            return total <= 0 ? 0 : (int) Math.min(100, bytesDone * 100 / total);
        }
    }

    private final Context atakContext;
    private final GitHubSource source;
    private final InstalledStore store;
    private final File downloadDir;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService listingThread = Executors.newSingleThreadExecutor();
    private final ExecutorService downloadThread = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new ArrayList<>();
    private final List<JobListener> jobListeners = new ArrayList<>();
    private final Map<String, Job> jobs = Collections.synchronizedMap(new LinkedHashMap<String, Job>());
    /** Local paths GhostMaps is removing itself (so the delete event isn't read as the user's). */
    private final Set<String> removing = Collections.synchronizedSet(new HashSet<String>());

    private volatile GitHubSource.Listing listing;
    private volatile boolean refreshing;
    private volatile String listingError;
    private volatile boolean disposed;
    private volatile OtherSources otherSources = OtherSources.NONE;

    GhostMapsManager(Context atakContext, File dataDir) {
        this.atakContext = atakContext;
        this.source = new GitHubSource(dataDir);
        this.store = new InstalledStore(new File(dataDir, "installed.json"));
        this.downloadDir = source.getDownloadDir();
        try {
            URIContentManager.getInstance().registerListener(this);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not watch ATAK's file list", e);
        }
        listingThread.execute(new Runnable() {
            @Override
            public void run() {
                GitHubSource.Listing l = source.cached();
                if (listing == null && l != null) {
                    listing = l;
                    relink(l.catalog);
                    notifyChanged();
                }
                try {
                    store.pruneMissing();
                } catch (IOException e) {
                    Log.w(TAG, "could not update installed list", e);
                }
                source.cleanDownloads();
            }
        });
    }

    // ---------------------------------------------------------------- state

    void addListener(Listener l) {
        listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void addJobListener(JobListener l) {
        jobListeners.add(l);
    }

    GitHubSource.Listing getListing() {
        return listing;
    }

    Catalog getCatalog() {
        GitHubSource.Listing l = listing;
        return l == null ? null : l.catalog;
    }

    boolean isRefreshing() {
        return refreshing;
    }

    /** Why there is no listing at all (first run offline), or null. */
    String getListingError() {
        return listingError;
    }

    InstalledStore getStore() {
        return store;
    }

    /** Files ATAK has loaded from sources other than GhostMaps. */
    OtherSources getOtherSources() {
        return otherSources;
    }

    Job getJob(String repoPath) {
        return jobs.get(repoPath);
    }

    List<UpdateCheck.Status> getStatuses() {
        Catalog c = getCatalog();
        List<UpdateCheck.Status> out = new ArrayList<>();
        if (c == null)
            return out;
        return UpdateCheck.checkAll(store, c);
    }

    // -------------------------------------------------------------- actions

    /**
     * Called when the panel opens: re-checks GitHub if the listing is stale
     * (or always when forced) and re-scans what ATAK has loaded.
     */
    void refresh(boolean force) {
        scanDevice();
        GitHubSource.Listing l = listing;
        if (refreshing)
            return;
        if (!force && l != null && l.notice == null
                && System.currentTimeMillis() - l.checkedAt < STALE_AFTER_MS)
            return;
        refreshing = true;
        notifyChanged();
        listingThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    listing = source.refresh();
                    listingError = null;
                    relink(listing.catalog);
                } catch (IOException e) {
                    listingError = e.getMessage();
                }
                refreshing = false;
                notifyChanged();
            }
        });
    }

    /** Points records at files that moved in the repo (no download). */
    private void relink(Catalog c) {
        try {
            if (UpdateCheck.relinkMoved(store, c) > 0)
                notifyChanged();
        } catch (IOException e) {
            Log.w(TAG, "could not update installed list", e);
        }
    }

    /**
     * Brings the installed list in line with the device and notes what
     * other sources have loaded. UI thread.
     */
    private void scanDevice() {
        List<String> mine = new ArrayList<>();
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (InstalledFile f : store.all()) {
            mine.add(f.getLocalPath());
            File local = new File(f.getLocalPath());
            if (!local.exists()) {
                // deleted outside GhostMaps (Overlay Manager, file manager)
                forget(f);
                changed = true;
            } else if (!Installer.isOurs(local) && !Installer.isLoaded(local)) {
                // left in atak/overlays, never loaded, by the first test build
                // (its import task stopped loading files after the first one)
                changed |= adoptLegacy(f, local);
            } else if (jobs.isEmpty() && now - f.getInstalledAt() > HEAL_AFTER_MS
                    && Installer.isOurs(local) && !Installer.isLoaded(local)) {
                // file is there but ATAK lost track of it: ask ATAK again
                Pair<String, String> cm = Installer.kmlContentMime(local);
                if (cm != null)
                    Installer.requestImport(local, cm.first, cm.second);
            }
        }
        otherSources = new OtherSources(Installer.loadedElsewhere(mine));
        if (changed)
            notifyChanged();
    }

    /**
     * Moves a file the first test build left unloaded into atak/GhostMaps and
     * loads it there. No download. UI thread.
     */
    private boolean adoptLegacy(InstalledFile f, File local) {
        Pair<String, String> cm = Installer.kmlContentMime(local);
        if (cm == null)
            return false;
        File dir = Installer.installDir();
        if (!dir.isDirectory() && !dir.mkdirs())
            return false;
        Set<String> taken = new HashSet<>();
        String[] present = dir.list();
        if (present != null)
            for (String n : present)
                taken.add(n.toLowerCase(Locale.US));
        File dest = new File(dir, LocalNames.unique(local.getName(), taken));
        try {
            if (!local.renameTo(dest)) {
                copy(local, dest);
                if (!local.delete())
                    Log.w(TAG, "could not delete " + local);
            }
            store.put(f.withLocalPath(dest.getAbsolutePath(), cm.first, cm.second,
                    System.currentTimeMillis()));
        } catch (IOException e) {
            Log.w(TAG, "could not move " + local, e);
            return false;
        }
        Installer.requestImport(dest, cm.first, cm.second);
        return true;
    }

    /**
     * Downloads and imports {@code f}. With {@code replaceOlder}, older
     * installed versions of the same product are removed once it's in;
     * an earlier install of the same file always is.
     */
    void install(final RepoFile f, boolean replaceOlder) {
        Job existing = jobs.get(f.getPath());
        if (existing != null && existing.phase != Phase.FAILED)
            return;
        final Job job = new Job(f, replaceOlder);
        jobs.put(f.getPath(), job);
        notifyChanged();
        downloadThread.execute(new Runnable() {
            @Override
            public void run() {
                runJob(job);
            }
        });
    }

    /** Installs every pending update (one download per target). */
    int updateAll() {
        List<RepoFile> targets = new ArrayList<>();
        for (UpdateCheck.Status s : getStatuses())
            if (s.hasUpdate() && !targets.contains(s.target))
                targets.add(s.target);
        for (RepoFile t : targets)
            install(t, true);
        return targets.size();
    }

    void cancel(String repoPath) {
        Job j = jobs.get(repoPath);
        if (j == null)
            return;
        j.cancel.set(true);
        if (j.phase == Phase.FAILED || j.phase == Phase.QUEUED) {
            jobs.remove(repoPath);
            notifyChanged();
        }
    }

    /** Removes an installed file from ATAK and forgets it. UI thread. */
    void remove(InstalledFile f) {
        removing.add(f.getLocalPath());
        Installer.remove(f);
        forget(f);
        notifyChanged();
    }

    private void forget(InstalledFile f) {
        try {
            store.remove(f.getRepoPath());
        } catch (IOException e) {
            Log.w(TAG, "could not update installed list", e);
        }
    }

    void dispose() {
        disposed = true;
        try {
            URIContentManager.getInstance().unregisterListener(this);
        } catch (RuntimeException ignored) {
            // ATAK shutting down
        }
        synchronized (jobs) {
            for (Job j : jobs.values())
                j.cancel.set(true);
        }
        listingThread.shutdownNow();
        downloadThread.shutdownNow();
        listeners.clear();
        jobListeners.clear();
    }

    // ------------------------------------------------- ATAK file list events

    @Override
    public void onContentImported(URIContentHandler h) {
        rescanSoon();
    }

    @Override
    public void onContentDeleted(URIContentHandler h) {
        if (h instanceof FileContentHandler) {
            File f = ((FileContentHandler) h).getFile();
            if (f != null) {
                final String path = f.getAbsolutePath();
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (removing.remove(path))
                            return; // our own removal
                        InstalledFile mine = store.findByLocalPath(path);
                        if (mine != null) {
                            // user removed it in the Overlay Manager: respect that
                            forget(mine);
                            File local = new File(path);
                            if (Installer.isOurs(local) && local.exists() && !local.delete())
                                Log.w(TAG, "could not delete " + local);
                            notifyChanged();
                        }
                    }
                });
            }
        }
        rescanSoon();
    }

    @Override
    public void onContentChanged(URIContentHandler h) {
    }

    private final Runnable rescan = new Runnable() {
        @Override
        public void run() {
            if (disposed)
                return;
            List<String> mine = new ArrayList<>();
            for (InstalledFile f : store.all())
                mine.add(f.getLocalPath());
            otherSources = new OtherSources(Installer.loadedElsewhere(mine));
            notifyChanged();
        }
    };

    private void rescanSoon() {
        ui.removeCallbacks(rescan);
        ui.postDelayed(rescan, 1000);
    }

    // ------------------------------------------------------------- workers

    private void runJob(final Job job) {
        if (job.cancel.get() || disposed) {
            jobs.remove(job.file.getPath());
            notifyChanged();
            return;
        }
        job.phase = Phase.DOWNLOADING;
        notifyChanged();
        final File downloaded = new File(downloadDir, job.file.getSha() + ".done");
        final File local;
        try {
            source.download(job.file, downloaded, new GitHubSource.Progress() {
                @Override
                public void onProgress(long done, long total) {
                    job.bytesDone = done;
                    notifyChanged();
                }
            }, job.cancel);
            local = place(downloaded, job.file.fileName());
        } catch (GitHubSource.CancelledException e) {
            // partial file is kept so the next attempt resumes
            jobs.remove(job.file.getPath());
            notifyChanged();
            return;
        } catch (IOException e) {
            Log.w(TAG, "download failed: " + job.file, e);
            fail(job, GitHubSource.describe(e));
            return;
        }

        job.phase = Phase.IMPORTING;
        notifyChanged();
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (disposed)
                    return;
                Installer.importInPlace(atakContext, local, new Installer.Callback() {
                    @Override
                    public void onImported(String content, String mime, boolean confirmed) {
                        onJobImported(job, local, content, mime, confirmed);
                    }

                    @Override
                    public void onFailed(String reason) {
                        if (local.exists() && !local.delete())
                            Log.w(TAG, "could not delete " + local);
                        fail(job, reason);
                    }
                });
            }
        });
    }

    /**
     * Moves a verified download into atak/GhostMaps under its repo file name,
     * or "name (2).kmz" if that's in use, so the file being replaced stays
     * untouched until the new one is loaded.
     */
    private File place(File downloaded, String fileName) throws IOException {
        File dir = Installer.installDir();
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
        Set<String> taken = new HashSet<>();
        String[] present = dir.list();
        if (present != null)
            for (String n : present)
                taken.add(n.toLowerCase(Locale.US));
        for (InstalledFile f : store.all())
            taken.add(new File(f.getLocalPath()).getName().toLowerCase(Locale.US));
        File dest = new File(dir, LocalNames.unique(fileName, taken));
        if (!downloaded.renameTo(dest)) {
            copy(downloaded, dest);
            //noinspection ResultOfMethodCallIgnored
            downloaded.delete();
        }
        return dest;
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from);
                OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
        }
    }

    private void onJobImported(Job job, File local, String content, String mime,
            boolean confirmed) {
        RepoFile f = job.file;
        List<InstalledFile> replaced = UpdateCheck.replacedBy(f, job.replaceOlder,
                store, getCatalog());
        for (InstalledFile old : replaced) {
            forget(old);
            if (!old.getLocalPath().equals(local.getAbsolutePath())) {
                removing.add(old.getLocalPath());
                Installer.remove(old);
            }
        }
        try {
            store.put(new InstalledFile(f.getPath(), f.getSha(), f.getSize(),
                    local.getAbsolutePath(), content, mime,
                    System.currentTimeMillis()));
        } catch (IOException e) {
            Log.w(TAG, "could not update installed list", e);
        }
        jobs.remove(f.getPath());
        notifyChanged();
        fireDone(f, null, !confirmed);
    }

    private void fail(Job job, String reason) {
        job.phase = Phase.FAILED;
        job.error = reason;
        notifyChanged();
        fireDone(job.file, reason == null ? "" : reason, false);
    }

    private void fireDone(final RepoFile f, final String error, final boolean stillLoading) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (disposed)
                    return;
                for (JobListener l : new ArrayList<>(jobListeners))
                    l.onJobDone(f, error, stillLoading);
            }
        });
    }

    private final AtomicBoolean pending = new AtomicBoolean();
    private final Runnable dispatch = new Runnable() {
        @Override
        public void run() {
            pending.set(false);
            if (disposed)
                return;
            for (Listener l : new ArrayList<>(listeners))
                l.onStateChanged();
        }
    };

    /** Coalesces bursts (download progress) into one UI refresh per frame-ish. */
    private void notifyChanged() {
        if (pending.compareAndSet(false, true))
            ui.postDelayed(dispatch, 100);
    }
}
