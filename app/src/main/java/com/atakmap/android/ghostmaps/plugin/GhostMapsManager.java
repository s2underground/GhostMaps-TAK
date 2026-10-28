package com.atakmap.android.ghostmaps.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.InstalledFile;
import com.atakmap.android.ghostmaps.plugin.core.InstalledStore;
import com.atakmap.android.ghostmaps.plugin.core.RepoFile;
import com.atakmap.android.ghostmaps.plugin.core.UpdateCheck;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * State behind the pane: the repo listing, what's installed and the download
 * queue. Downloads run one at a time on a background thread (kind to slow
 * links); imports run on the UI thread as ATAK requires. Listeners are told
 * about every change on the UI thread.
 */
final class GhostMapsManager {

    private static final String TAG = "GhostMaps";

    /** Re-check GitHub on pane open if the listing is older than this. */
    static final long STALE_AFTER_MS = 10 * 60 * 1000L;

    interface Listener {
        void onStateChanged();
    }

    /** Told once per finished job (error is null on success), on the UI thread. */
    interface JobListener {
        void onJobDone(RepoFile file, String error);
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

    private volatile GitHubSource.Listing listing;
    private volatile boolean refreshing;
    private volatile String listingError;
    private volatile boolean disposed;

    GhostMapsManager(Context atakContext, File dataDir) {
        this.atakContext = atakContext;
        this.source = new GitHubSource(dataDir);
        this.store = new InstalledStore(new File(dataDir, "installed.json"));
        this.downloadDir = source.getDownloadDir();
        listingThread.execute(new Runnable() {
            @Override
            public void run() {
                GitHubSource.Listing l = source.cached();
                if (listing == null && l != null) {
                    listing = l;
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

    Job getJob(String repoPath) {
        return jobs.get(repoPath);
    }

    boolean hasActiveJobs() {
        synchronized (jobs) {
            for (Job j : jobs.values())
                if (j.phase != Phase.FAILED)
                    return true;
        }
        return false;
    }

    List<UpdateCheck.Status> getStatuses() {
        Catalog c = getCatalog();
        List<UpdateCheck.Status> out = new ArrayList<>();
        if (c == null)
            return out;
        return UpdateCheck.checkAll(store, c);
    }

    // -------------------------------------------------------------- actions

    /** Re-reads the listing from GitHub; skipped if fresh unless forced. */
    void refresh(boolean force) {
        GitHubSource.Listing l = listing;
        if (refreshing)
            return;
        if (!force && l != null && l.notice == null
                && System.currentTimeMillis() - l.checkedAt < STALE_AFTER_MS) {
            // listing is fresh, but the user may have removed files in the
            // Overlay Manager since we last looked
            listingThread.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (store.pruneMissing() > 0)
                            notifyChanged();
                    } catch (IOException e) {
                        Log.w(TAG, "could not update installed list", e);
                    }
                }
            });
            return;
        }
        refreshing = true;
        notifyChanged();
        listingThread.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    listing = source.refresh();
                    listingError = null;
                } catch (IOException e) {
                    listingError = e.getMessage();
                }
                try {
                    store.pruneMissing();
                } catch (IOException e) {
                    Log.w(TAG, "could not update installed list", e);
                }
                refreshing = false;
                notifyChanged();
            }
        });
    }

    /**
     * Downloads and imports {@code f}. With {@code replaceOlder}, older
     * installed versions of the same product are removed once it's in.
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

    /** Installs every pending update (one download per product). */
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

    /** Removes an installed file from ATAK and forgets it. */
    void remove(InstalledFile f) {
        Installer.remove(f);
        try {
            store.remove(f.getRepoPath());
        } catch (IOException e) {
            Log.w(TAG, "could not update installed list", e);
        }
        notifyChanged();
    }

    void dispose() {
        disposed = true;
        synchronized (jobs) {
            for (Job j : jobs.values())
                j.cancel.set(true);
        }
        listingThread.shutdownNow();
        downloadThread.shutdownNow();
        listeners.clear();
        jobListeners.clear();
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
        final File dest = new File(downloadDir, job.file.fileName());
        try {
            source.download(job.file, dest, new GitHubSource.Progress() {
                @Override
                public void onProgress(long done, long total) {
                    job.bytesDone = done;
                    notifyChanged();
                }
            }, job.cancel);
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
                Installer.importFile(atakContext, dest, new Installer.Callback() {
                    @Override
                    public void onImported(File local, String content, String mime) {
                        onJobImported(job, local, content, mime);
                    }

                    @Override
                    public void onFailed(String reason) {
                        //noinspection ResultOfMethodCallIgnored
                        dest.delete();
                        fail(job, reason);
                    }
                });
            }
        });
    }

    private void onJobImported(Job job, File local, String content, String mime) {
        RepoFile f = job.file;
        List<InstalledFile> older = new ArrayList<>();
        if (job.replaceOlder) {
            for (InstalledFile inst : store.all()) {
                RepoFile r = inst.asRepoFile();
                if (!r.getPath().equals(f.getPath())
                        && r.productKey().equals(f.productKey())
                        && r.parsed().compareAge(f.parsed()) < 0)
                    older.add(inst);
            }
        }
        try {
            store.put(new InstalledFile(f.getPath(), f.getSha(), f.getSize(),
                    local.getAbsolutePath(), content, mime,
                    System.currentTimeMillis()));
        } catch (IOException e) {
            Log.w(TAG, "could not update installed list", e);
        }
        for (InstalledFile old : older) {
            // never delete the file we just imported, even if paths collide
            if (!new File(old.getLocalPath()).getAbsolutePath()
                    .equals(local.getAbsolutePath()))
                Installer.remove(old);
            try {
                store.remove(old.getRepoPath());
            } catch (IOException e) {
                Log.w(TAG, "could not update installed list", e);
            }
        }
        jobs.remove(f.getPath());
        notifyChanged();
        fireDone(f, null);
    }

    private void fail(Job job, String reason) {
        job.phase = Phase.FAILED;
        job.error = reason;
        notifyChanged();
        fireDone(job.file, reason == null ? "" : reason);
    }

    private void fireDone(final RepoFile f, final String error) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (disposed)
                    return;
                for (JobListener l : new ArrayList<>(jobListeners))
                    l.onJobDone(f, error);
            }
        });
    }

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
    private final AtomicBoolean pending = new AtomicBoolean();

    /** Coalesces bursts (download progress) into one UI refresh per frame-ish. */
    private void notifyChanged() {
        if (pending.compareAndSet(false, true))
            ui.postDelayed(dispatch, 100);
    }
}
