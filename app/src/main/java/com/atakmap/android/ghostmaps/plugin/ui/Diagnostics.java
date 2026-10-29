package com.atakmap.android.ghostmaps.plugin.ui;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import com.atakmap.android.ghostmaps.plugin.BuildConfig;
import com.atakmap.android.ghostmaps.plugin.engine.GhostMapsEngine;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Writes a troubleshooting report: the engine's view of every install and
 * job, plus recent GhostMaps-related lines from ATAK's own log. Read-only
 * towards ATAK (it never walks the Overlay Manager or touches its lists).
 * The file stays on the device; nothing is sent anywhere.
 */
public final class Diagnostics {

    public interface Done {
        void onDone(File report, String error);
    }

    private static final Pattern INTERESTING = Pattern.compile(
            "(?i)GhostMaps|IconsMapAdapter|AddIconset|ImportReceiver|KmlFileSpatialDb|SpatialDb"
                    + "|URIContent|Exception");
    private static final int MAX_LOG_LINES = 400;

    private Diagnostics() {
    }

    /** Collects and writes the report off the UI thread; {@code done} runs on the UI thread. */
    public static void write(GhostMapsEngine engine, final File out, final Done done) {
        engine.dumpState(new GhostMapsEngine.Callback<String>() {
            @Override
            public void accept(final String engineState) {
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        String error = null;
                        try {
                            writeReport(out, engineState);
                        } catch (IOException | RuntimeException e) {
                            error = String.valueOf(e.getMessage());
                        }
                        final String err = error;
                        new Handler(Looper.getMainLooper()).post(new Runnable() {
                            @Override
                            public void run() {
                                done.onDone(out, err);
                            }
                        });
                    }
                }, "GhostMaps-diagnostics").start();
            }
        });
    }

    private static void writeReport(File out, String engineState) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("GhostMaps troubleshooting report ")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()))
                .append("\nplugin ").append(BuildConfig.VERSION_NAME)
                .append(" · Android ").append(Build.VERSION.RELEASE)
                .append(" · ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append("\n\n== GhostMaps state ==\n").append(engineState)
                .append("\n== Recent log lines (this app only) ==\n").append(recentLog());
        File dir = out.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(sb.toString());
        }
    }

    /**
     * Matching lines from this process's log (Android lets an app read its
     * own). {@code --pid} needs Android 7; before that the lines are
     * filtered by the PID column of the threadtime format instead.
     */
    private static String recentLog() {
        StringBuilder sb = new StringBuilder();
        java.lang.Process p = null;
        String pid = Integer.toString(Process.myPid());
        boolean pidOption = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;
        try {
            List<String> cmd = new ArrayList<>(Arrays.asList("logcat", "-d", "-v", "threadtime", "-t", "5000"));
            if (pidOption)
                cmd.add("--pid=" + pid);
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            int kept = 0;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (kept < MAX_LOG_LINES && (pidOption || pid.equals(pidColumn(line)))
                            && INTERESTING.matcher(line).find()) {
                        sb.append(line).append('\n');
                        kept++;
                    }
                }
            }
            if (kept == 0)
                sb.append("(nothing matched)\n");
        } catch (IOException | RuntimeException e) {
            sb.append("could not read the log: ").append(e).append('\n');
        } finally {
            if (p != null)
                p.destroy();
        }
        return sb.toString();
    }

    /** The PID column of a threadtime line ("10-29 08:00:00.000  1234  1250 W Tag: …"). */
    static String pidColumn(String line) {
        String[] f = line.trim().split("\\s+", 4);
        return f.length >= 3 ? f[2] : null;
    }
}
