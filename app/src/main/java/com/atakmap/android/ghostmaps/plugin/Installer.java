package com.atakmap.android.ghostmaps.plugin;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

import com.atakmap.android.data.FileContentHandler;
import com.atakmap.android.data.URIContentHandler;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.ghostmaps.plugin.core.InstalledFile;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.importexport.ImportReceiver;
import com.atakmap.android.importfiles.task.ImportFileTask;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.spatial.file.KmlFileSpatialDb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Loads GhostMaps files into ATAK and takes them out again, keeping strictly
 * to its own folder ({@code atak/GhostMaps/}).
 *
 * <p>Files are imported <em>in place</em>: ATAK reads them where GhostMaps
 * put them. Nothing is copied into {@code atak/overlays/}, so a KMZ that
 * DataSync, the Import Manager or a data package loaded under the same name
 * is never overwritten, moved or deleted. Removal only ever targets files
 * GhostMaps recorded as its own.</p>
 */
final class Installer {

    private static final String TAG = "GhostMaps.Installer";

    /** atak/GhostMaps: where installed files live. */
    static final String FOLDER = "GhostMaps";

    /** Import outcome; delivered on the UI thread. */
    interface Callback {
        /** ATAK has loaded the file ({@code confirmed}) or is still working on it. */
        void onImported(String contentType, String mimeType, boolean confirmed);

        void onFailed(String reason);
    }

    private Installer() {
    }

    static File installDir() {
        return FileSystemUtils.getItem(FOLDER);
    }

    /** True if {@code f} is inside GhostMaps' own folder. */
    static boolean isOurs(File f) {
        try {
            String dir = installDir().getCanonicalPath() + File.separator;
            return f.getCanonicalPath().startsWith(dir);
        } catch (IOException e) {
            return false;
        }
    }

    /** True if ATAK currently has {@code f} loaded. UI thread. */
    static boolean isLoaded(File f) {
        try {
            if (URIContentManager.getInstance().getHandler(f) != null)
                return true;
            // the lookup is by exact path string; compare canonical paths too
            // (/sdcard/... vs /storage/emulated/0/...)
            String want = f.getCanonicalPath();
            for (URIContentHandler h : URIContentManager.getInstance().getRegisteredHandlers()) {
                if (h instanceof FileContentHandler) {
                    File hf = ((FileContentHandler) h).getFile();
                    if (hf != null && hf.getCanonicalPath().equals(want))
                        return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "could not check whether " + f + " is loaded", e);
        }
        return false;
    }

    /**
     * Files ATAK has loaded that aren't GhostMaps' (DataSync, Import
     * Manager, data packages…), minus {@code exclude} (GhostMaps' own
     * records). UI thread.
     */
    static List<String> loadedElsewhere(Collection<String> exclude) {
        Set<String> skip = new HashSet<>(exclude);
        List<String> out = new ArrayList<>();
        try {
            for (URIContentHandler h : URIContentManager.getInstance().getRegisteredHandlers()) {
                if (!(h instanceof FileContentHandler))
                    continue;
                File f = ((FileContentHandler) h).getFile();
                if (f == null || isOurs(f) || skip.contains(f.getAbsolutePath()))
                    continue;
                out.add(f.getAbsolutePath());
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "could not list loaded files", e);
        }
        return out;
    }

    /**
     * Loads {@code file} (already in GhostMaps' folder) into ATAK where it
     * is. KMZ/KML go straight to ATAK's KML importer; other types (icon sets,
     * GPX, …) through the Import Manager's sorter with "import in place".
     * UI thread.
     */
    static void importInPlace(Context atakContext, final File file, final Callback cb) {
        final Pair<String, String> kml = kmlContentMime(file);
        if (kml != null) {
            requestImport(file, kml.first, kml.second);
            waitUntilLoaded(file, kml, cb);
            return;
        }

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
                        Pair<String, String> cm = contentMime(result);
                        cb.onImported(cm == null ? "" : cm.first,
                                cm == null ? "" : cm.second, true);
                    }
                });
        task.addFlag(ImportFileTask.FlagValidateExt
                | ImportFileTask.FlagImportInPlace
                | ImportFileTask.FlagPromptOnMultipleMatch
                | ImportFileTask.FlagShowNotificationsDuringImport);
        task.execute(file.getAbsolutePath());

        // ImportFileTask calls back only on success; on failure it shows its
        // own alert. Watch for it finishing without a callback.
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            private int waited;

            @Override
            public void run() {
                if (done[0])
                    return;
                if (task.getStatus() == AsyncTask.Status.FINISHED || task.isCancelled()) {
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

    /** Asks ATAK's KML importer to load {@code file} where it is. */
    static void requestImport(File file, String contentType, String mimeType) {
        Intent i = new Intent(ImportExportMapComponent.ACTION_IMPORT_DATA);
        i.putExtra("contentType", contentType);
        i.putExtra("mimeType", mimeType);
        i.putExtra("uri", Uri.fromFile(file).toString());
        i.putExtra("showNotifications", true);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    /** Polls until ATAK lists the file as loaded; big files get more time. */
    private static void waitUntilLoaded(final File file, final Pair<String, String> cm,
            final Callback cb) {
        final long deadline = System.currentTimeMillis()
                + Math.min(5 * 60_000L, 30_000L + file.length() / (1024 * 1024) * 3_000L);
        final Handler h = new Handler(Looper.getMainLooper());
        h.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isLoaded(file)) {
                    cb.onImported(cm.first, cm.second, true);
                } else if (System.currentTimeMillis() > deadline) {
                    // keep it: ATAK may still be parsing a large file, and the
                    // next panel check re-requests the import if it never lands
                    cb.onImported(cm.first, cm.second, false);
                } else {
                    h.postDelayed(this, 1000);
                }
            }
        }, 750);
    }

    /**
     * Takes an installed file out of ATAK and deletes it. Only acts on the
     * file GhostMaps recorded; files outside atak/GhostMaps are never
     * deleted directly. UI thread.
     */
    static void remove(InstalledFile f) {
        final File local = new File(f.getLocalPath());
        URIContentHandler handler = null;
        try {
            handler = URIContentManager.getInstance().getHandler(local);
        } catch (RuntimeException ignored) {
            // fall through to the intent
        }
        if (handler != null) {
            handler.deleteContent();
        } else {
            String content = f.getContentType();
            String mime = f.getMimeType();
            if (content.isEmpty()) {
                Pair<String, String> cm = kmlContentMime(local);
                if (cm != null) {
                    content = cm.first;
                    mime = cm.second;
                }
            }
            if (!content.isEmpty())
                ImportReceiver.remove(Uri.fromFile(local), content, mime);
        }
        if (!isOurs(local))
            return; // e.g. a 1.0 test install in atak/overlays: ATAK removes it
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

    static Pair<String, String> kmlContentMime(File f) {
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
