package com.atakmap.android.ghostmaps.plugin.atak;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.atakmap.android.data.FileContentHandler;
import com.atakmap.android.data.URIContentHandler;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.ghostmaps.plugin.core.DeviceState;
import com.atakmap.android.ghostmaps.plugin.engine.Platform;
import com.atakmap.android.icons.IconsMapAdapter;
import com.atakmap.android.icons.UserIconDatabase;
import com.atakmap.android.icons.UserIconSet;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.importexport.ImportReceiver;
import com.atakmap.android.ipc.AtakBroadcast;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;
import com.atakmap.filesystem.HashingUtils;
import com.atakmap.spatial.file.KmlFileSpatialDb;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The real {@link Platform}: talks to ATAK through its documented intents
 * and registries only. Each file kind uses ATAK's own mechanism for it:
 *
 * <table>
 * <tr><th>Kind</th><th>Install</th><th>Loaded?</th><th>Remove</th></tr>
 * <tr><td>KMZ/KML</td><td>{@code ACTION_IMPORT_DATA} (content type KML, file
 * URI) — loads the file where it is</td><td>a {@link FileContentHandler} is
 * registered for it</td><td>the handler's {@code deleteContent()} (ATAK
 * unloads and deletes the file)</td></tr>
 * <tr><td>Icon-set ZIP</td><td>{@code IconsMapAdapter.ADD_ICONSET} (ATAK
 * copies the icons into its icon database)</td><td>the UID is in
 * {@link UserIconDatabase}</td><td>{@code REMOVE_ICONSET}</td></tr>
 * </table>
 *
 * GhostMaps never uses ATAK's Import Manager task ({@code ImportFileTask}):
 * it detaches ATAK's shared importer listeners after each run and can move
 * files into ATAK's own folders.
 */
public final class AtakPlatform implements Platform {

    private static final String TAG = "GhostMaps";

    private final Context atakContext;
    private final Handler main = new Handler(Looper.getMainLooper());

    public AtakPlatform(Context atakContext) {
        this.atakContext = atakContext;
    }

    @Override
    public DeviceState probe() {
        try {
            List<String> loaded = new ArrayList<>();
            for (URIContentHandler h : URIContentManager.getInstance().getRegisteredHandlers()) {
                if (h instanceof FileContentHandler) {
                    File f = ((FileContentHandler) h).getFile();
                    if (f != null)
                        loaded.add(canonical(f));
                }
            }
            Set<String> uids = new HashSet<>();
            List<UserIconSet> sets = UserIconDatabase.instance(atakContext).getIconSets(false, false);
            if (sets != null)
                for (UserIconSet s : sets)
                    if (s != null && s.getUid() != null)
                        uids.add(s.getUid());
            File root = FileSystemUtils.getRoot();
            boolean storage = root != null && root.isDirectory() && root.canRead();
            return new DeviceState(loaded, uids, storage);
        } catch (RuntimeException e) {
            Log.w(TAG, "couldn't read ATAK's loaded files or icon sets", e);
            return null;
        }
    }

    @Override
    public void importMapLayer(final File file, final boolean notifyUser) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(ImportExportMapComponent.ACTION_IMPORT_DATA);
                i.putExtra("contentType", KmlFileSpatialDb.KML_CONTENT_TYPE);
                i.putExtra("mimeType", kmlMime(file));
                i.putExtra("uri", Uri.fromFile(file).toString());
                i.putExtra("showNotifications", notifyUser);
                AtakBroadcast.getInstance().sendBroadcast(i);
            }
        });
    }

    @Override
    public void removeMapLayer(final File file) {
        main.post(new Runnable() {
            @Override
            public void run() {
                URIContentHandler h = null;
                try {
                    h = URIContentManager.getInstance().getHandler(file);
                } catch (RuntimeException e) {
                    Log.w(TAG, "couldn't look up " + file, e);
                }
                if (h != null)
                    h.deleteContent();
                else
                    ImportReceiver.remove(Uri.fromFile(file),
                            KmlFileSpatialDb.KML_CONTENT_TYPE, kmlMime(file));
            }
        });
    }

    @Override
    public void installIconSet(final File zip) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(IconsMapAdapter.ADD_ICONSET);
                i.putExtra("filepath", zip.getAbsolutePath());
                i.putExtra("show_progress", false); // GhostMaps shows its own progress
                AtakBroadcast.getInstance().sendBroadcast(i);
            }
        });
    }

    @Override
    public void removeIconSet(final String uid) {
        main.post(new Runnable() {
            @Override
            public void run() {
                Intent i = new Intent(IconsMapAdapter.REMOVE_ICONSET);
                i.putExtra("iconsetUid", uid);
                AtakBroadcast.getInstance().sendBroadcast(i);
            }
        });
    }

    @Override
    public String iconSetUid(File zip, String declaredUid) throws IOException {
        if (declaredUid != null && !declaredUid.isEmpty())
            return declaredUid;
        // ATAK's rule for a ZIP without a usable iconset.xml (IconsMapAdapter.addIconset)
        String sha = HashingUtils.sha256sum(zip);
        if (sha == null || sha.isEmpty())
            throw new IOException("couldn't read the icon set");
        return sha;
    }

    @Override
    public String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException e) {
            return file.getAbsolutePath();
        }
    }

    private static String kmlMime(File f) {
        return f.getName().toLowerCase(Locale.US).endsWith(".kml")
                ? KmlFileSpatialDb.KML_FILE_MIME_TYPE
                : KmlFileSpatialDb.KMZ_FILE_MIME_TYPE;
    }
}
