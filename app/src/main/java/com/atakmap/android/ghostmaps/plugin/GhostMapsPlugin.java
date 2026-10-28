package com.atakmap.android.ghostmaps.plugin;

import android.content.Context;
import android.widget.Toast;

import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.ghostmaps.plugin.core.RepoFile;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;

import gov.tak.api.plugin.IPlugin;
import gov.tak.api.plugin.IServiceController;
import gov.tak.api.ui.IHostUIService;
import gov.tak.api.ui.Pane;
import gov.tak.api.ui.PaneBuilder;
import gov.tak.api.ui.ToolbarItem;
import gov.tak.api.ui.ToolbarItemAdapter;
import gov.tak.platform.marshal.MarshalManager;

import java.io.File;

/**
 * Plugin entry point. Adds a "GhostMaps" toolbar button that opens the data
 * manager pane; tapping it again closes the pane. Downloads keep running
 * with the pane closed and report back with a toast.
 */
public class GhostMapsPlugin implements IPlugin {

    /** Plugin data (listing cache, installed list, partial downloads). */
    static final String DATA_DIR = FileSystemUtils.TOOL_DATA_DIRECTORY + "/ghostmaps";

    private final Context pluginContext;
    private final IHostUIService uiService;
    private final ToolbarItem toolbarItem;

    private GhostMapsManager manager;
    private Pane pane;
    private GhostMapsPane ghostPane;

    public GhostMapsPlugin(IServiceController serviceController) {
        final PluginContextProvider ctxProvider = serviceController
                .getService(PluginContextProvider.class);
        pluginContext = ctxProvider != null
                ? ctxProvider.getPluginContext()
                : null;
        if (pluginContext != null)
            pluginContext.setTheme(R.style.ATAKPluginTheme);

        uiService = serviceController.getService(IHostUIService.class);

        if (pluginContext == null) {
            toolbarItem = null;
            return;
        }

        // the identifier must stay stable or ATAK loses the button's
        // position whenever the user rearranges the toolbar
        toolbarItem = new ToolbarItem.Builder(
                pluginContext.getString(R.string.app_name),
                MarshalManager.marshal(
                        pluginContext.getResources()
                                .getDrawable(R.drawable.ic_launcher),
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
        // start watching ATAK's file list right away, so files removed in the
        // Overlay Manager are noticed even if the panel is never opened
        MapView mv = MapView.getMapView();
        if (mv != null && pluginContext != null)
            manager(mv);
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
        if (manager != null)
            manager.dispose();
        ghostPane = null;
        manager = null;
        pane = null;
    }

    private GhostMapsManager manager(final MapView mapView) {
        if (manager == null) {
            File dir = FileSystemUtils.getItem(DATA_DIR);
            manager = new GhostMapsManager(mapView.getContext(), dir);
            manager.addJobListener(new GhostMapsManager.JobListener() {
                @Override
                public void onJobDone(RepoFile file, String error, boolean stillLoading) {
                    String name = file.parsed().getProduct();
                    String msg = error != null
                            ? pluginContext.getString(R.string.toast_failed, name, error)
                            : stillLoading
                                    ? pluginContext.getString(R.string.toast_still_loading, name)
                                    : pluginContext.getString(R.string.toast_imported, name);
                    Toast.makeText(mapView.getContext(), msg,
                            error == null ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG).show();
                }
            });
        }
        return manager;
    }

    private void togglePane() {
        if (uiService == null)
            return;

        if (pane != null && uiService.isPaneVisible(pane)) {
            uiService.closePane(pane);
            return;
        }

        if (pane == null) {
            MapView mapView = MapView.getMapView();
            if (mapView == null)
                return;
            ghostPane = new GhostMapsPane(pluginContext, mapView.getContext(),
                    manager(mapView));
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
        if (ghostPane != null)
            ghostPane.onShown();
    }
}
