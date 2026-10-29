package com.atakmap.android.ghostmaps.plugin.engine;

import com.atakmap.android.ghostmaps.plugin.core.FileKind;

/** Timing rules, in one place. */
public final class Tuning {

    /** Re-check the repository on panel open if the listing is older than this. */
    public static final long LISTING_STALE_MS = 10 * 60 * 1000L;

    /** How often a waiting install checks whether ATAK has loaded the file. */
    static final long CONFIRM_POLL_MS = 1000;

    /** A file must be missing this long (across checks) before its record is dropped. */
    static final long MISSING_GRACE_MS = 10_000;

    /** ATAK gets this long to unload a layer before GhostMaps checks again. */
    static final long REMOVE_GRACE_MS = 15_000;

    /** No automatic reloads this soon after start (ATAK may still be loading layers). */
    static final long STARTUP_QUIET_MS = 60_000;

    /** Coalesces bursts of ATAK file-list events into one check. */
    static final long EVENT_DEBOUNCE_MS = 1000;

    /** Download progress is published at most this often. */
    static final long PROGRESS_PUBLISH_MS = 250;

    private Tuning() {
    }

    /** How long to wait for ATAK to load a file before calling it "still loading". */
    static long confirmTimeoutMs(FileKind kind, long sizeBytes) {
        long mb = Math.max(0, sizeBytes) / (1024 * 1024);
        if (kind == FileKind.ICON_SET)
            return Math.min(180_000L, 60_000L + mb * 2_000L);
        return Math.min(300_000L, 30_000L + mb * 3_000L);
    }
}
