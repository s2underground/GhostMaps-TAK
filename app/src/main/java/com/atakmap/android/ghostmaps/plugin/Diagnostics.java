package com.atakmap.android.ghostmaps.plugin;

import android.os.Build;
import android.os.Process;

import com.atakmap.android.data.FileContentHandler;
import com.atakmap.android.data.URIContentHandler;
import com.atakmap.android.data.URIContentManager;
import com.atakmap.android.ghostmaps.plugin.core.InstalledFile;
import com.atakmap.android.hierarchy.HierarchyListItem;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.overlay.MapOverlay;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Writes a troubleshooting report to atak/GhostMaps/ghostmaps-diagnostics.txt
 * (long-press the refresh button): what GhostMaps installed and whether ATAK
 * lists each file as loaded, every file ATAK has loaded, each Overlay Manager
 * entry and whether building it fails, and recent warnings/errors from
 * ATAK's own log (this app's process only). Nothing is sent anywhere.
 */
final class Diagnostics {

    private static final Pattern INTERESTING = Pattern.compile(
            "(?i)exception|error|\\bat [a-z]|GhostMaps|Hierarchy|Overlay|Import|Kml|KMZ|FileContent|SpatialDb");

    private Diagnostics() {
    }

    /** Builds the report; call on the UI thread (overlay models expect it). Returns the file. */
    static File write(GhostMapsManager mgr) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("GhostMaps diagnostics ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                .append("\nplugin ").append(BuildConfig.VERSION_NAME)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\n\n== Installed by GhostMaps ==\n");
        for (InstalledFile f : mgr.getStore().all()) {
            File local = new File(f.getLocalPath());
            sb.append(local.exists() ? (Installer.isLoaded(local) ? "[loaded]  " : "[NOT loaded] ")
                    : "[file missing] ")
                    .append(f.getLocalPath()).append("\n    from ").append(f.getRepoPath())
                    .append("  type=").append(f.getContentType()).append('\n');
        }

        sb.append("\n== Files ATAK has loaded ==\n");
        try {
            for (URIContentHandler h : URIContentManager.getInstance().getRegisteredHandlers()) {
                sb.append(h.getClass().getSimpleName()).append("  ");
                if (h instanceof FileContentHandler) {
                    FileContentHandler fh = (FileContentHandler) h;
                    File f = fh.getFile();
                    sb.append(fh.getContentType()).append("  ")
                            .append(f == null ? "?" : f.getAbsolutePath())
                            .append(f != null && !f.exists() ? "  (FILE MISSING)" : "");
                } else {
                    sb.append(h.getURI());
                }
                sb.append('\n');
            }
        } catch (RuntimeException e) {
            sb.append("could not list: ").append(trace(e)).append('\n');
        }

        sb.append("\n== Overlay Manager entries ==\n");
        MapView mv = MapView.getMapView();
        if (mv != null) {
            List<MapOverlay> overlays;
            try {
                overlays = new ArrayList<>(mv.getMapOverlayManager().getOverlays());
            } catch (RuntimeException e) {
                overlays = new ArrayList<>();
                sb.append("could not list: ").append(trace(e)).append('\n');
            }
            HierarchyListItem.Sort sort = new HierarchyListItem.Sort() {
            };
            for (MapOverlay o : overlays) {
                sb.append(o.getName()).append("  [").append(o.getIdentifier()).append("]  ");
                try {
                    HierarchyListItem item = o.getListModel(null, 0, sort);
                    if (item == null) {
                        sb.append("no list model");
                    } else {
                        item.refresh(sort);
                        sb.append(item.getChildCount()).append(" children");
                    }
                } catch (Throwable t) {
                    sb.append("FAILED: ").append(trace(t));
                }
                sb.append('\n');
            }
        }

        sb.append("\n== Recent log lines (this app only) ==\n");
        sb.append(recentLog());

        File out = new File(Installer.installDir(), "ghostmaps-diagnostics.txt");
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
        return out;
    }

    /** Warnings/errors from this process's log; Android lets an app read its own. */
    private static String recentLog() {
        StringBuilder sb = new StringBuilder();
        java.lang.Process p = null;
        try {
            p = new ProcessBuilder("logcat", "-d", "-v", "time", "-t", "6000",
                    "--pid=" + Process.myPid()).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int kept = 0;
                while ((line = r.readLine()) != null) {
                    if (INTERESTING.matcher(line).find()) {
                        sb.append(line).append('\n');
                        kept++;
                    }
                }
                if (kept == 0)
                    sb.append("(nothing matched)\n");
            }
        } catch (IOException | RuntimeException e) {
            sb.append("could not read log: ").append(e).append('\n');
        } finally {
            if (p != null)
                p.destroy();
        }
        return sb.toString();
    }

    private static String trace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        String s = sw.toString();
        return s.length() > 3000 ? s.substring(0, 3000) + "…" : s;
    }
}
