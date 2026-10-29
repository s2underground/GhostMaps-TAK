package com.atakmap.android.ghostmaps.plugin;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.ghostmaps.plugin.atak.AtakEvents;
import com.atakmap.android.ghostmaps.plugin.atak.AtakPlatform;
import com.atakmap.android.ghostmaps.plugin.core.Clock;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.engine.EngineLog;
import com.atakmap.android.ghostmaps.plugin.engine.GhostMapsEngine;
import com.atakmap.android.ghostmaps.plugin.engine.Snapshot;
import com.atakmap.android.ghostmaps.plugin.net.RepoClient;
import com.atakmap.android.ghostmaps.plugin.ui.Diagnostics;
import com.atakmap.android.ghostmaps.plugin.ui.GhostMapsPane;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;
import com.atakmap.coremap.log.Log;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

/**
 * Plugin entry point and wiring. {@code onStart} builds the engine and its
 * ATAK adapters; {@code onStop} tears all of it down (listeners, threads,
 * dialogs, the pane), so a plugin reload starts clean.
 */
public class GhostMapsPlugin implements IPlugin {

    private static final String TAG = "GhostMaps";

    /** GhostMaps' own folder for installed files: atak/GhostMaps. */
    static final String INSTALL_DIR = "GhostMaps";
    /** Plugin data: listing cache, install records, partial downloads, reports. */
    static final String DATA_DIR = FileSystemUtils.TOOL_DATA_DIRECTORY + "/ghostmaps";

    static final String OWNER = "s2underground";
    static final String REPO = "GhostMaps";
    static final String BRANCH = "main";

    private final Context pluginContext;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;

    private GhostMapsEngine engine;
    private AtakEvents events;
    private GhostMapsPane ghostPane;
    private Pane pane;

    public GhostMapsPlugin(IServiceController serviceController) {
        PluginContextProvider ctxProvider = serviceController.getService(PluginContextProvider.class);
        pluginContext = ctxProvider != null ? ctxProvider.getPluginContext() : null;
        if (pluginContext != null)
            pluginContext.setTheme(R.style.ATAKPluginTheme);
        uiService = serviceController.getService(IHostUIService.class);
        if (pluginContext == null) {
            toolbarItem = null;
            return;
        }
        // the identifier must stay stable or ATAK loses the button's toolbar position
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources().getDrawable(R.drawable.ic_launcher),
                        android.graphics.drawable.Drawable.class,
                        gov.tak.api.commons.graphics.Bitmap.class))
                .setListener(new ToolbarItemAdapter() {
                    @Override
                    public void onClick(ToolbarItem item) {
                        togglePane();
                    }
                })
                .setIdentifier(pluginContext.getPackageName())
                .build();
    }

    @Override
    public void onStart() {
        if (uiService != null && toolbarItem != null)
            uiService.addToolbarItem(toolbarItem);
        MapView mv = MapView.getMapView();
        if (mv == null || pluginContext == null)
            return;
        startEngine(mv.getContext());
    }

    @Override
    public void onStop() {
        if (uiService != null) {
            if (pane != null && uiService.isPaneVisible(pane))
                uiService.closePane(pane);
            if (toolbarItem != null)
                uiService.removeToolbarItem(toolbarItem);
        }
        if (ghostPane != null)
            ghostPane.dispose();
        if (events != null)
            events.unregister();
        if (engine != null)
            engine.dispose();
        ghostPane = null;
        pane = null;
        events = null;
        engine = null;
    }

    private void startEngine(final Context atakContext) {
        File dataDir = FileSystemUtils.getItem(DATA_DIR);
        File installDir = FileSystemUtils.getItem(INSTALL_DIR);
        final Handler main = new Handler(Looper.getMainLooper());
        Executor ui = new Executor() {
            @Override
            public void execute(Runnable r) {
                main.post(r);
            }
        };
        ScheduledExecutorService state = Executors.newSingleThreadScheduledExecutor(named("GhostMaps-state"));
        ExecutorService net = Executors.newFixedThreadPool(2, named("GhostMaps-net"));

        final RepoClient repo = new RepoClient(RepoClient.GITHUB_API, RepoClient.GITHUB_RAW,
                OWNER, REPO, BRANCH, dataDir, Clock.SYSTEM, RepoClient.realSleeper());
        net.execute(new Runnable() {
            @Override
            public void run() {
                repo.cleanDownloads();
            }
        });

        engine = new GhostMapsEngine(new AtakPlatform(atakContext), repo,
                new File(dataDir, "installed.json"), installDir, repo.downloadDir(),
                state, net, ui, Clock.SYSTEM, new EngineLog() {
                    @Override
                    public void i(String msg) {
                        Log.i(TAG, msg);
                    }

                    @Override
                    public void w(String msg, Throwable t) {
                        Log.w(TAG, msg, t);
                    }
                });
        engine.addListener(new GhostMapsEngine.Listener() {
            @Override
            public void onSnapshot(Snapshot snapshot) {
            }

            @Override
            public void onInstallFinished(RepoEntry target, String error, boolean stillLoading) {
                String name = target.name().getProduct();
                String msg = error != null
                        ? pluginContext.getString(R.string.toast_failed, name, error)
                        : stillLoading
                                ? pluginContext.getString(R.string.toast_still_loading, name)
                                : pluginContext.getString(R.string.toast_installed, name);
                Toast.makeText(atakContext, msg,
                        error == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
            }
        });
        events = new AtakEvents(engine);
        events.register();
        engine.start();
    }

    private static ThreadFactory named(final String name) {
        return new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, name);
                t.setDaemon(true);
                return t;
            }
        };
    }

    private void togglePane() {
        if (uiService == null || engine == null)
            return;
        if (pane != null && uiService.isPaneVisible(pane)) {
            uiService.closePane(pane);
            return;
        }
        final MapView mapView = MapView.getMapView();
        if (mapView == null)
            return;
        if (pane == null) {
            ghostPane = new GhostMapsPane(pluginContext, mapView.getContext(), engine,
                    new Runnable() {
                        @Override
                        public void run() {
                            writeDiagnostics(mapView.getContext());
                        }
                    });
            pane = new PaneBuilder(ghostPane.getView())
                    .setMetaValue(Pane.RELATIVE_LOCATION, Pane.Location.Default)
                    .setMetaValue(Pane.PREFERRED_WIDTH_RATIO, 0.45D)
                    .setMetaValue(Pane.PREFERRED_HEIGHT_RATIO, 0.6D)
                    .build();
        }
        uiService.showPane(pane, new IHostUIService.IPaneLifecycleListener() {
            @Override
            public void onPaneVisible(boolean visible) {
                if (visible && ghostPane != null)
                    ghostPane.onShown();
            }

            @Override
            public void onPaneClose() {
            }
        });
    }

    private void writeDiagnostics(final Context atakContext) {
        if (engine == null)
            return;
        File out = new File(FileSystemUtils.getItem(DATA_DIR), "diagnostics.txt");
        Diagnostics.write(engine, out, new Diagnostics.Done() {
            @Override
            public void onDone(File report, String error) {
                String msg = error == null
                        ? pluginContext.getString(R.string.diag_saved, report.getAbsolutePath())
                        : pluginContext.getString(R.string.diag_failed, error);
                Toast.makeText(atakContext, msg, Toast.LENGTH_LONG).show();
            }
        });
    }
}
