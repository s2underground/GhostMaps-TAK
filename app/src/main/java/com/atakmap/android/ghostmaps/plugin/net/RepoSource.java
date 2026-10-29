package com.atakmap.android.ghostmaps.plugin.net;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;

import java.io.File;
import java.io.IOException;

/** Where published files come from. {@link RepoClient} is the GitHub implementation. */
public interface RepoSource {

    /** A repository listing and where it came from. Immutable. */
    final class Listing {
        public final Catalog catalog;
        /** When the source last confirmed this listing (ms since epoch). */
        public final long checkedAt;
        /** Null when fresh; otherwise why a saved copy is shown. */
        public final String notice;
        public final boolean truncated;

        public Listing(Catalog catalog, long checkedAt, String notice, boolean truncated) {
            this.catalog = catalog;
            this.checkedAt = checkedAt;
            this.notice = notice;
            this.truncated = truncated;
        }

        public Listing withNotice(String n) {
            return new Listing(catalog, checkedAt, n, truncated);
        }
    }

    interface Progress {
        void onProgress(long bytesDone, long bytesTotal);
    }

    /** Thrown when the user cancelled a download. */
    final class CancelledException extends IOException {
        private static final long serialVersionUID = 1L;

        public CancelledException() {
            super("Cancelled");
        }
    }

    /** No listing could be obtained and none is saved; message is user-facing. */
    final class NoListingException extends IOException {
        private static final long serialVersionUID = 1L;

        public NoListingException(String msg) {
            super(msg);
        }
    }

    /** The saved listing, or null. Does disk I/O. */
    Listing cached();

    /**
     * Fetches the current listing. Falls back to the saved one (with a
     * {@link Listing#notice}) when offline or rate-limited.
     *
     * @throws NoListingException when there's nothing to show at all
     */
    Listing refresh() throws IOException;

    /**
     * Downloads {@code entry} as published in {@code commitSha} to
     * {@code dest}, resuming a partial download, verifying the git blob SHA.
     */
    void download(RepoEntry entry, String commitSha, File dest, Progress progress,
            CancelToken cancel) throws IOException;
}
