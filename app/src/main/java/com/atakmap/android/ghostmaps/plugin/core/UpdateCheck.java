package com.atakmap.android.ghostmaps.plugin.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compares what GhostMaps installed against the current repo listing.
 *
 * <p>An installed file is found again even after the repo is reorganized,
 * in this order: same path; same contents anywhere (moved or renamed,
 * unchanged); same file name anywhere (moved and changed); otherwise the
 * newest file of the same product anywhere (see
 * {@link RepoFile#productKey()}).</p>
 */
public final class UpdateCheck {

    public enum Kind {
        /** Installed file is the newest version and unchanged. */
        UP_TO_DATE,
        /** Same file, moved or renamed in the repo, contents unchanged; nothing to download. */
        MOVED,
        /** Same file, but its contents changed on GitHub (e.g. a rolling 30-day pull). */
        CHANGED,
        /** A newer dated/numbered version of the same product exists. */
        NEWER_VERSION,
        /** A newer version exists and is already installed too; this one can go. */
        SUPERSEDED,
        /** Gone from the repo and nothing newer replaces it. */
        REMOVED_FROM_REPO
    }

    /** Result for one installed file. */
    public static final class Status {
        public final InstalledFile installed;
        public final Kind kind;
        /**
         * The repo file to install for CHANGED/NEWER_VERSION, the newer
         * installed one for SUPERSEDED, the file's new location for MOVED.
         */
        public final RepoFile target;

        Status(InstalledFile installed, Kind kind, RepoFile target) {
            this.installed = installed;
            this.kind = kind;
            this.target = target;
        }

        public boolean hasUpdate() {
            return kind == Kind.CHANGED || kind == Kind.NEWER_VERSION;
        }
    }

    private UpdateCheck() {
    }

    /**
     * Where {@code inst} is in the current listing, or null: by path, then
     * by identical contents (preferring the same file name), then by file
     * name.
     */
    public static RepoFile locate(InstalledFile inst, Catalog catalog) {
        RepoFile f = catalog.find(inst.getRepoPath());
        if (f != null)
            return f;
        String name = inst.asRepoFile().fileName();
        List<RepoFile> same = catalog.findBySha(inst.getSha());
        if (!same.isEmpty()) {
            for (RepoFile s : same)
                if (s.fileName().equalsIgnoreCase(name))
                    return s;
            return same.get(0);
        }
        List<RepoFile> named = catalog.findByName(name);
        return named.isEmpty() ? null : named.get(0);
    }

    public static Status check(InstalledFile inst, Catalog catalog,
            InstalledStore store) {
        RepoFile mine = inst.asRepoFile();
        RepoFile located = locate(inst, catalog);
        List<RepoFile> versions = located != null ? catalog.versionsOf(located)
                : catalog.versionsOf(mine.productKey());
        RepoFile latest = versions.isEmpty() ? null : versions.get(0);

        if (latest != null && latest.parsed().compareAge(mine.parsed()) > 0
                && (located == null || !latest.getPath().equals(located.getPath()))) {
            if (store != null && isInstalled(store, latest))
                return new Status(inst, Kind.SUPERSEDED, latest);
            return new Status(inst, Kind.NEWER_VERSION, latest);
        }
        if (located == null) {
            // renamed beyond recognition but same product and age: offer it
            if (latest != null && latest.parsed().compareAge(mine.parsed()) == 0
                    && !(store != null && isInstalled(store, latest)))
                return new Status(inst, Kind.CHANGED, latest);
            return new Status(inst, Kind.REMOVED_FROM_REPO, null);
        }
        if (!located.getSha().equals(inst.getSha()))
            return new Status(inst, Kind.CHANGED, located);
        if (!located.getPath().equals(inst.getRepoPath()))
            return new Status(inst, Kind.MOVED, located);
        return new Status(inst, Kind.UP_TO_DATE, null);
    }

    public static List<Status> checkAll(InstalledStore store, Catalog catalog) {
        List<Status> out = new ArrayList<>();
        for (InstalledFile f : store.all())
            out.add(check(f, catalog, store));
        return out;
    }

    /**
     * Points installed records at their file's new location when the file
     * moved or was renamed in the repo with unchanged contents, so later
     * checks and the Browse view see it as installed. No download. Returns
     * how many records changed.
     */
    public static int relinkMoved(InstalledStore store, Catalog catalog) throws IOException {
        int n = 0;
        for (InstalledFile inst : store.all()) {
            if (catalog.find(inst.getRepoPath()) != null)
                continue;
            RepoFile now = locate(inst, catalog);
            if (now == null || !now.getSha().equals(inst.getSha())
                    || store.get(now.getPath()) != null)
                continue;
            store.replace(inst.getRepoPath(), inst.withRepoPath(now.getPath()));
            n++;
        }
        return n;
    }

    /**
     * Installed records that installing {@code target} replaces (to be
     * removed once it's in): earlier installs of the same file (wherever it
     * was in the repo) and, with {@code replaceOlder}, older or same-age
     * versions of the same product.
     */
    public static List<InstalledFile> replacedBy(RepoFile target, boolean replaceOlder,
            InstalledStore store, Catalog catalog) {
        List<InstalledFile> out = new ArrayList<>();
        for (InstalledFile r : store.all()) {
            if (r.getSha().equals(target.getSha())
                    && !r.getRepoPath().equals(target.getPath()))
                continue; // identical copy recorded elsewhere: leave it
            RepoFile at = catalog == null ? null : locate(r, catalog);
            boolean sameFile = r.getRepoPath().equals(target.getPath())
                    || (at != null && at.getPath().equals(target.getPath()));
            RepoFile mine = r.asRepoFile();
            boolean olderSameProduct = replaceOlder
                    && mine.productKey().equals(target.productKey())
                    && mine.parsed().compareAge(target.parsed()) <= 0;
            if (sameFile || olderSameProduct)
                out.add(r);
        }
        return out;
    }

    /** Count of installed files with a download waiting (one per target). */
    public static int countUpdates(List<Status> statuses) {
        Set<String> targets = new HashSet<>();
        for (Status s : statuses)
            if (s.hasUpdate())
                targets.add(s.target.getPath());
        return targets.size();
    }

    private static boolean isInstalled(InstalledStore store, RepoFile f) {
        InstalledFile at = store.get(f.getPath());
        if (at != null && at.getSha().equals(f.getSha()))
            return true;
        for (InstalledFile i : store.all())
            if (i.getSha().equals(f.getSha()))
                return true;
        return false;
    }
}
