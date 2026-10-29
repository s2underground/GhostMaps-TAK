package com.atakmap.android.ghostmaps.plugin.engine;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.InstallRecord;
import com.atakmap.android.ghostmaps.plugin.core.OtherSources;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.core.UpdatePlanner;
import com.atakmap.android.ghostmaps.plugin.net.RepoSource;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything the panel shows, at one moment. Immutable and complete, so the
 * UI renders from it without touching ATAK, the disk or the network.
 */
public final class Snapshot {

    /** What is true on the device for one record, independent of updates. */
    public enum Presence {
        /** Map layer loaded in ATAK / icon set present. */
        ACTIVE,
        /** ATAK was asked to load it and may still be working. */
        LOADING,
        /** The file is here but ATAK doesn't have it loaded. */
        NOT_LOADED,
        /** Being removed. */
        REMOVING,
        /** From an early test build, outside GhostMaps' folder; GhostMaps won't touch it. */
        UNMANAGED
    }

    public enum JobPhase {
        QUEUED, DOWNLOADING, INSTALLING, WAITING, FAILED
    }

    public static final class RecordView {
        public final InstallRecord record;
        public final Presence presence;
        /** Null when there's no listing yet. */
        public final UpdatePlanner.Status status;

        public RecordView(InstallRecord record, Presence presence, UpdatePlanner.Status status) {
            this.record = record;
            this.presence = presence;
            this.status = status;
        }

        public boolean hasUpdate() {
            return status != null && status.hasUpdate();
        }
    }

    public static final class JobView {
        public final RepoEntry target;
        public final JobPhase phase;
        /** 0–100 while downloading. */
        public final int percent;
        /** FAILED only. */
        public final String error;
        /** True when the job replaces installed record(s), i.e. is an update. */
        public final boolean isUpdate;

        public JobView(RepoEntry target, JobPhase phase, int percent, String error, boolean isUpdate) {
            this.target = target;
            this.phase = phase;
            this.percent = percent;
            this.error = error;
            this.isUpdate = isUpdate;
        }
    }

    public final RepoSource.Listing listing;
    public final boolean refreshing;
    /** Why there's no listing at all (first run offline), or null. */
    public final String listingError;
    public final List<RecordView> records;
    /** Keyed by target repo path. */
    public final Map<String, JobView> jobs;
    public final OtherSources otherSources;
    public final int updateCount;

    private final Map<String, RecordView> byRepoPath = new HashMap<>();
    private final Map<String, RecordView> bySha = new HashMap<>();

    public Snapshot(RepoSource.Listing listing, boolean refreshing, String listingError,
            List<RecordView> records, Map<String, JobView> jobs, OtherSources otherSources,
            int updateCount) {
        this.listing = listing;
        this.refreshing = refreshing;
        this.listingError = listingError;
        this.records = Collections.unmodifiableList(records);
        this.jobs = Collections.unmodifiableMap(jobs);
        this.otherSources = otherSources;
        this.updateCount = updateCount;
        for (RecordView v : records) {
            byRepoPath.put(v.record.repoPath(), v);
            if (v.record.isManaged() && !bySha.containsKey(v.record.sha()))
                bySha.put(v.record.sha(), v);
        }
    }

    public Catalog catalog() {
        return listing == null ? null : listing.catalog;
    }

    /** The installed record for this repository file (same path, else same contents), or null. */
    public RecordView recordFor(RepoEntry e) {
        RecordView v = byRepoPath.get(e.path());
        return v != null ? v : bySha.get(e.sha());
    }

    /** An installed record in the same product group as {@code e} (an older or other version), or null. */
    public RecordView otherVersionOf(RepoEntry e) {
        for (RecordView v : records)
            if (v.record.isManaged() && v.record.asEntry().groupKey().equals(e.groupKey())
                    && !v.record.sha().equals(e.sha()))
                return v;
        return null;
    }

    public JobView jobFor(String repoPath) {
        return jobs.get(repoPath);
    }
}
