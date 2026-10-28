package com.atakmap.android.ghostmaps.plugin.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Compares what GhostMaps installed against the current repo listing.
 */
public final class UpdateCheck {

    public enum Kind {
        /** Installed file is the newest version and unchanged. */
        UP_TO_DATE,
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
        /** The repo file to install for CHANGED/NEWER_VERSION (or the newer installed one for SUPERSEDED). */
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

    public static Status check(InstalledFile inst, Catalog catalog,
            InstalledStore store) {
        RepoFile mine = inst.asRepoFile();
        RepoFile inRepo = catalog.find(inst.getRepoPath());
        RepoFile latest = inRepo != null ? catalog.latestOf(inRepo)
                : latestByKey(catalog, mine);

        if (latest != null && !latest.getPath().equals(inst.getRepoPath())
                && latest.parsed().compareAge(mine.parsed()) > 0) {
            InstalledFile other = store == null ? null : store.get(latest.getPath());
            if (other != null && other.getSha().equals(latest.getSha()))
                return new Status(inst, Kind.SUPERSEDED, latest);
            return new Status(inst, Kind.NEWER_VERSION, latest);
        }
        if (inRepo == null)
            return new Status(inst, Kind.REMOVED_FROM_REPO, null);
        if (!inRepo.getSha().equals(inst.getSha()))
            return new Status(inst, Kind.CHANGED, inRepo);
        return new Status(inst, Kind.UP_TO_DATE, null);
    }

    public static List<Status> checkAll(InstalledStore store, Catalog catalog) {
        List<Status> out = new ArrayList<>();
        for (InstalledFile f : store.all())
            out.add(check(f, catalog, store));
        return out;
    }

    /** Count of installed files with a download waiting (one per product). */
    public static int countUpdates(List<Status> statuses) {
        java.util.Set<String> targets = new java.util.HashSet<>();
        for (Status s : statuses)
            if (s.hasUpdate())
                targets.add(s.target.getPath());
        return targets.size();
    }

    private static RepoFile latestByKey(Catalog catalog, RepoFile mine) {
        String key = mine.productKey();
        RepoFile best = null;
        for (RepoFile f : catalog.getFiles())
            if (f.productKey().equals(key)
                    && (best == null || Catalog.NEWEST_FIRST.compare(f, best) < 0))
                best = f;
        return best;
    }
}
