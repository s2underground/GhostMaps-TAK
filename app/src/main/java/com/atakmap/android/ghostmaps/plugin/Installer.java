package com.atakmap.android.ghostmaps.plugin;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import com.atakmap.android.ghostmaps.plugin.core.InstalledFile;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.importfiles.task.ImportFileTask;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.coremap.log.Log;
import com.atakmap.spatial.file.KmlFileSpatialDb;

import java.io.File;
import java.util.Locale;

/**
 * Hands downloaded files to ATAK's own importer, the same path the Import
 * Manager uses, so they land in the usual folders (KML/KMZ in
 * atak/overlays), show up under Overlay Manager → File Overlays and are
 * searchable in QuickSearch. Removal goes through ATAK's DELETE_DATA intent.
 */
final class Installer {

    private static final String TAG = "GhostMaps.Installer";

    /** Import outcome; delivered on the UI thread. */
    interface Callback {
        void onImported(File localFile, String contentType, String mimeType);

        void onFailed(String reason);
    }

    private Installer() {
    }

    /**
     * Imports {@code file} (moved into ATAK's folder for its type). Must be
     * called on the UI thread; ATAK shows its own dialog if it can't tell
     * what the file is.
     */
    static void importFile(Context atakContext, final File file, final Callback cb) {
        final boolean[] done = { false };
        final ImportFileTask task = new ImportFileTask(atakContext,
                new ImportFileTask.Callback() {
                    @Override
                    public void onCallback(Context ctx, ImportFileTask.Result result) {
                        if (done[0])
                            return;
                        done[0] = true;
                        if (result == null || !result.isSuccess()) {
                            String err = result == null ? null : result.getError();
                            cb.onFailed(err == null || err.isEmpty()
                                    ? "ATAK could not import the file" : err);
                            return;
                        }
                        File local = result.getFile() != null ? result.getFile() : file;
                        Pair<String, String> cm = contentMime(result);
                        if (cm == null)
                            cm = guessContentMime(local);
                        cb.onImported(local, cm == null ? "" : cm.first,
                                cm == null ? "" : cm.second);
                    }
                });
        // match ATAK's Import Manager: check the extension, keep KMZ as KML
        // (not a mission package), ask the user only if the file is ambiguous
        task.addFlag(ImportFileTask.FlagValidateExt
                | ImportFileTask.FlagKMZStrictMode
                | ImportFileTask.FlagPromptOnMultipleMatch
                | ImportFileTask.FlagShowNotificationsDuringImport);
        task.execute(file.getAbsolutePath());

        // ImportFileTask only calls back on success; on failure it shows its
        // own alert. Watch for it finishing without a callback.
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            private int waited;

            @Override
            public void run() {
                if (done[0])
                    return;
                if (task.getStatus() == AsyncTask.Status.FINISHED
                        || task.isCancelled()) {
                    // give onPostExecute's callback a moment to arrive
                    if (waited++ > 1) {
                        done[0] = true;
                        cb.onFailed("ATAK could not import the file");
                    } else {
                        h.postDelayed(this, 500);
                    }
                    return;
                }
                h.postDelayed(this, 1000);
            }
        }, 1000);
    }

    /**
     * Removes an installed file from ATAK (Overlay Manager entry and file).
     * Safe to call from any thread.
     */
    static void remove(InstalledFile f) {
        final File local = new File(f.getLocalPath());
        String content = f.getContentType();
        String mime = f.getMimeType();
        if (content.isEmpty()) {
            Pair<String, String> cm = guessContentMime(local);
            if (cm != null) {
                content = cm.first;
                mime = cm.second;
            }
        }
        if (!content.isEmpty()) {
            Intent i = new Intent(ImportExportMapComponent.ACTION_DELETE_DATA);
            i.putExtra("contentType", content);
            i.putExtra("mimeType", mime);
            i.putExtra("uri", Uri.fromFile(local).toString());
            AtakBroadcast.getInstance().sendBroadcast(i);
        }
        // ATAK's importer normally deletes the file itself; clean up if not
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (local.exists() && !local.delete())
                    Log.w(TAG, "could not delete " + local);
            }
        }, 5000);
    }

    private static Pair<String, String> contentMime(ImportFileTask.Result r) {
        try {
            if (r instanceof ImportFileTask.Result2) {
                gov.tak.api.importfiles.ImportResolver res = ((ImportFileTask.Result2) r).getResolver();
                if (res != null)
                    return res.getContentMIME();
            }
            if (r.getSorter() != null)
                return r.getSorter().getContentMIME();
        } catch (RuntimeException e) {
            Log.w(TAG, "no content type from importer", e);
        }
        return null;
    }

    private static Pair<String, String> guessContentMime(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        if (n.endsWith(".kmz"))
            return new Pair<>(KmlFileSpatialDb.KML_CONTENT_TYPE,
                    KmlFileSpatialDb.KMZ_FILE_MIME_TYPE);
        if (n.endsWith(".kml"))
            return new Pair<>(KmlFileSpatialDb.KML_CONTENT_TYPE,
                    KmlFileSpatialDb.KML_FILE_MIME_TYPE);
        return null;
    }
}
