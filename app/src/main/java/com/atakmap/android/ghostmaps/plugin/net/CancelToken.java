package com.atakmap.android.ghostmaps.plugin.net;

import java.net.HttpURLConnection;

/**
 * Cancels a download promptly: besides setting a flag checked between
 * reads, it disconnects the open connection so a blocked read returns at
 * once instead of waiting for the read timeout.
 */
public final class CancelToken {

    private volatile boolean cancelled;
    private HttpURLConnection connection;

    public void cancel() {
        HttpURLConnection c;
        synchronized (this) {
            cancelled = true;
            c = connection;
        }
        if (c != null)
            c.disconnect();
    }

    public boolean isCancelled() {
        return cancelled;
    }

    synchronized void attach(HttpURLConnection c) {
        connection = c;
        if (cancelled)
            c.disconnect();
    }

    synchronized void detach() {
        connection = null;
    }
}
