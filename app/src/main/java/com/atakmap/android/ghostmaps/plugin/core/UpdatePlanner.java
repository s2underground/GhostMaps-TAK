package com.atakmap.android.ghostmaps.plugin.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out, for each installed file, what the current repository offers:
 * nothing new, the same file at a new path, changed contents, or a newer
 * version. Pure functions; no I/O.
 *
 * <h3>Safety rules</h3>
 * <ol>
 * <li>A record is matched to the listing only by <b>content</b> (identical
 * git blob SHA anywhere) or by <b>exact path</b>. File names alone never
 * identify a file.</li>
 * <li>"Newer version" only comes from the record's own product group (same
 * scope folder, kind, dated-ness and name; see {@link RepoEntry}), and
 * never points at a file in an archive folder.</li>
 * <li>Anything looser (same name in another folder) is a
 * {@link Status#suggestion} the user has to confirm; it is never applied
 * automatically.</li>
 * <li>An update replaces exactly the record(s) it was planned for.</li>
 * </ol>
 */
public final class UpdatePlanner {

    public enum Kind {
        /** Installed bytes are the newest live version at the same path. */
        UP_TO_DATE,
        /** Same bytes now live at another path; re-point the record, no download. */
        MOVED,
        /** Same path, new bytes (e.g. a rolling 30-day file). */
        CHANGED,
        /** A newer version of the same product exists in the same folder scope. */
        NEWER_VERSION,
        /** A newer version exists and is installed as well; this one can be removed. */
        SUPERSEDED,
        /** Not in the listing any more and no safe successor. */
        GONE,
        /** Installed by an early test build outside GhostMaps' folder; hands off. */
        UNMANAGED
    }

    /** Result for one record. */
    public static final class Status {
        public final InstallRecord record;
        public final Kind kind;
        /**
         * MOVED: the new location. CHANGED / NEWER_VERSION: what to install.
         * SUPERSEDED: the newer installed version. Otherwise null.
         */
        public final RepoEntry target;
        /** GONE only: a file that looks related, for the user to confirm. May be null. */
        public final RepoEntry suggestion;

        Status(InstallRecord record, Kind kind, RepoEntry target, RepoEntry suggestion) {
            this.record = record;
            this.kind = kind;
            this.target = target;
            this.suggestion = suggestion;
        }

        public boolean hasUpdate() {
            return kind == Kind.CHANGED || kind == Kind.NEWER_VERSION;
        }
    }

    private UpdatePlanner() {
    }

    /**
     * Where the record's bytes are in the listing, or null: same path with
     * same contents, else same contents anywhere (preferring the same file
     * name, then live folders), else null. Paths whose contents changed are
     * handled by {@link #evaluate}.
     */
    public static RepoEntry locateSameContent(InstallRecord r, Catalog c) {
        RepoEntry at = c.find(r.repoPath());
        if (at != null && at.sha().equals(r.sha()))
            return at;
        List<RepoEntry> same = c.findBySha(r.sha());
        if (same.isEmpty())
            return null;
        String name = r.asEntry().fileName();
        for (RepoEntry e : same)
            if (e.fileName().equalsIgnoreCase(name) && !e.isArchived())
                return e;
        for (RepoEntry e : same)
            if (e.fileName().equalsIgnoreCase(name))
                return e;
        for (RepoEntry e : same)
            if (!e.isArchived())
                return e;
        return same.get(0);
    }

    /**
     * @param installedShas SHAs of every managed record (to spot SUPERSEDED)
     */
    public static Status evaluate(InstallRecord r, Catalog c, Set<String> installedShas) {
        if (!r.isManaged())
            return new Status(r, Kind.UNMANAGED, null, null);
        RepoEntry mine = r.asEntry();

        RepoEntry same = locateSameContent(r, c);
        RepoEntry anchor; // the entry whose product group we follow
        if (same != null) {
            anchor = same;
        } else {
            RepoEntry atPath = c.find(r.repoPath());
            if (atPath != null) {
                // same path, different bytes: changed in place, unless the
                // group has something newer still
                RepoEntry newest = c.newestLive(atPath.groupKey());
                if (newest != null && !newest.path().equals(atPath.path())
                        && newest.name().compareAge(atPath.name()) > 0)
                    return newer(r, newest, installedShas);
                return new Status(r, Kind.CHANGED, atPath, null);
            }
            anchor = mine; // gone: follow its own (old) folder scope
        }

        RepoEntry newest = c.newestLive(anchor.groupKey());
        if (newest != null && newest.name().compareAge(mine.name()) > 0
                && !newest.sha().equals(r.sha()))
            return newer(r, newest, installedShas);

        if (same == null)
            return new Status(r, Kind.GONE, null, suggest(r, c));
        if (!same.path().equals(r.repoPath()))
            return new Status(r, Kind.MOVED, same, null);
        return new Status(r, Kind.UP_TO_DATE, null, null);
    }

    private static Status newer(InstallRecord r, RepoEntry newest, Set<String> installedShas) {
        if (installedShas.contains(newest.sha()))
            return new Status(r, Kind.SUPERSEDED, newest, null);
        return new Status(r, Kind.NEWER_VERSION, newest, null);
    }

    /** Same file name elsewhere, else same display name and kind elsewhere; newest live wins. */
    private static RepoEntry suggest(InstallRecord r, Catalog c) {
        RepoEntry mine = r.asEntry();
        for (RepoEntry e : c.findByFileName(mine.fileName()))
            if (!e.isArchived() && e.kind() == mine.kind())
                return e;
        for (RepoEntry e : c.looselyRelated(mine.looseProductKey()))
            if (!e.isArchived())
                return e;
        return null;
    }

    public static List<Status> evaluateAll(Collection<InstallRecord> records, Catalog c) {
        Set<String> shas = new HashSet<>();
        for (InstallRecord r : records)
            if (r.isManaged())
                shas.add(r.sha());
        List<Status> out = new ArrayList<>(records.size());
        for (InstallRecord r : records)
            out.add(evaluate(r, c, shas));
        return out;
    }

    /**
     * Records whose bytes moved to a new path (status MOVED), mapped to that
     * path. Skips a move when another record already sits at the new path.
     */
    public static Map<String, String> relinks(Collection<InstallRecord> records, Catalog c) {
        Set<String> taken = new HashSet<>();
        for (InstallRecord r : records)
            taken.add(r.repoPath());
        Map<String, String> out = new LinkedHashMap<>();
        for (InstallRecord r : records) {
            if (!r.isManaged())
                continue;
            RepoEntry same = locateSameContent(r, c);
            if (same != null && !same.path().equals(r.repoPath()) && !taken.contains(same.path())) {
                out.put(r.id(), same.path());
                taken.add(same.path());
            }
        }
        return out;
    }

    /**
     * Plan for "Update all": target path → IDs of the records it replaces.
     * Several old versions of one product share a single download.
     */
    public static Map<String, List<String>> updatePlan(List<Status> statuses) {
        Map<String, List<String>> plan = new LinkedHashMap<>();
        for (Status s : statuses) {
            if (!s.hasUpdate())
                continue;
            List<String> ids = plan.get(s.target.path());
            if (ids == null) {
                ids = new ArrayList<>();
                plan.put(s.target.path(), ids);
            }
            ids.add(s.record.id());
        }
        return plan;
    }

    public static int countUpdates(List<Status> statuses) {
        return updatePlan(statuses).size();
    }
}
