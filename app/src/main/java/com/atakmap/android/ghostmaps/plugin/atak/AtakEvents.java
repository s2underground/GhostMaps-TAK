package com.atakmap.android.ghostmaps.plugin.atak;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.atakmap.android.data.URIContentHandler;
import com.atakmap.android.data.URIContentListener;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.ghostmaps.plugin.engine.GhostMapsEngine;
import com.atakmap.android.icons.IconsMapAdapter;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.coremap.log.Log;

/**
 * Forwards ATAK's change notifications to the engine. Events are only hints
 * ("look again soon"); the engine decides from what it reads, never from an
 * event alone, so a misleading event can't cause a deletion.
 */
public final class AtakEvents {

    private static final String TAG = "GhostMaps";

    private final GhostMapsEngine engine;

    private final URIContentListener contentListener = new URIContentListener() {
        @Override
        public void onContentImported(URIContentHandler h) {
            engine.deviceChanged();
        }

        @Override
        public void onContentDeleted(URIContentHandler h) {
            engine.deviceChanged();
        }

        @Override
        public void onContentChanged(URIContentHandler h) {
            // visibility and similar changes don't matter to GhostMaps
        }
    };

    private final BroadcastReceiver iconSets = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (IconsMapAdapter.ICONSET_ADDED.equals(intent.getAction()))
                engine.iconSetAdded(intent.getStringExtra("uid"));
            else
                engine.deviceChanged();
        }
    };

    private boolean registered;

    public AtakEvents(GhostMapsEngine engine) {
        this.engine = engine;
    }

    public void register() {
        if (registered)
            return;
        try {
            URIContentManager.getInstance().registerListener(contentListener);
            AtakBroadcast.DocumentedIntentFilter f = new AtakBroadcast.DocumentedIntentFilter();
            f.addAction(IconsMapAdapter.ICONSET_ADDED, "GhostMaps: confirm icon-set installs");
            f.addAction(IconsMapAdapter.ICONSET_REMOVED, "GhostMaps: notice icon-set removals");
            AtakBroadcast.getInstance().registerReceiver(iconSets, f);
            registered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "couldn't watch ATAK's files and icon sets", e);
        }
    }

    public void unregister() {
        if (!registered)
            return;
        registered = false;
        try {
            URIContentManager.getInstance().unregisterListener(contentListener);
            AtakBroadcast.getInstance().unregisterReceiver(iconSets);
        } catch (RuntimeException e) {
            Log.w(TAG, "couldn't stop watching ATAK", e);
        }
    }
}
