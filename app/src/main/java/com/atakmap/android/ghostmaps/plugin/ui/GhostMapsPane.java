package com.atakmap.android.ghostmaps.plugin.ui;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.atakmap.android.ghostmaps.plugin.R;
import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.FileKind;
import com.atakmap.android.ghostmaps.plugin.core.Formats;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.core.UpdatePlanner;
import com.atakmap.android.ghostmaps.plugin.engine.GhostMapsEngine;
import com.atakmap.android.ghostmaps.plugin.engine.Snapshot;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The GhostMaps panel. Renders {@link Snapshot}s through {@link RowBuilder}
 * and turns taps into engine calls (after confirmation where it matters).
 * Holds only view state (tab, folder, filter); never reads ATAK, the disk or
 * the network itself.
 */
public final class GhostMapsPane implements GhostMapsEngine.Listener {

    /** Ask before downloading anything at least this big. */
    static final long CONFIRM_BYTES = 10L * 1024 * 1024;
    /** On cellular/hotspot, ask from this size. */
    static final long CONFIRM_BYTES_METERED = 2L * 1024 * 1024;

    private final Context plugin;
    private final Context atak;
    private final GhostMapsEngine engine;
    private final Runnable onDiagnostics;
    private final View root;
    private final Button tabLatest;
    private final Button tabBrowse;
    private final Button tabInstalled;
    private final Button updateAll;
    private final ImageButton refresh;
    private final ImageButton filterClear;
    private final ProgressBar refreshProgress;
    private final EditText filter;
    private final TextView status;
    private final TextView breadcrumb;
    private final Adapter adapter = new Adapter();
    private final List<AlertDialog> dialogs = new ArrayList<>();

    private RowBuilder.Tab tab = RowBuilder.Tab.LATEST;
    private String folder = "";
    private Snapshot snapshot;
    private boolean disposed;

    // the pane's root has no parent view to inflate into
    @SuppressLint("InflateParams")
    public GhostMapsPane(Context pluginContext, Context atakContext, GhostMapsEngine engine,
            Runnable onDiagnostics) {
        this.plugin = pluginContext;
        this.atak = atakContext;
        this.engine = engine;
        this.onDiagnostics = onDiagnostics;
        root = LayoutInflater.from(pluginContext).inflate(R.layout.ghostmaps_pane, null);
        tabLatest = root.findViewById(R.id.tab_latest);
        tabBrowse = root.findViewById(R.id.tab_browse);
        tabInstalled = root.findViewById(R.id.tab_installed);
        updateAll = root.findViewById(R.id.update_all);
        refresh = root.findViewById(R.id.refresh);
        refreshProgress = root.findViewById(R.id.refresh_progress);
        filter = root.findViewById(R.id.filter);
        filterClear = root.findViewById(R.id.filter_clear);
        status = root.findViewById(R.id.status);
        breadcrumb = root.findViewById(R.id.breadcrumb);
        ListView list = root.findViewById(R.id.list);
        list.setAdapter(adapter);
        wire(list);
        engine.addListener(this);
    }

    public View getView() {
        return root;
    }

    /** The panel became visible. */
    public void onShown() {
        engine.panelOpened();
    }

    public void dispose() {
        disposed = true;
        engine.removeListener(this);
        for (AlertDialog d : dialogs)
            if (d.isShowing())
                d.dismiss();
        dialogs.clear();
    }

    // ------------------------------------------------------------ engine events

    @Override
    public void onSnapshot(Snapshot s) {
        if (disposed)
            return;
        snapshot = s;
        render();
    }

    @Override
    public void onInstallFinished(RepoEntry target, String error, boolean stillLoading) {
        // the plugin shows a toast; the rows already reflect the outcome
    }

    // ----------------------------------------------------------------- wiring

    private void wire(ListView list) {
        View.OnClickListener tabs = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RowBuilder.Tab t = v == tabLatest ? RowBuilder.Tab.LATEST
                        : v == tabBrowse ? RowBuilder.Tab.BROWSE : RowBuilder.Tab.INSTALLED;
                if (t == RowBuilder.Tab.BROWSE && tab == RowBuilder.Tab.BROWSE)
                    folder = "";
                tab = t;
                render();
            }
        };
        tabLatest.setOnClickListener(tabs);
        tabBrowse.setOnClickListener(tabs);
        tabInstalled.setOnClickListener(tabs);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                engine.refresh();
            }
        });
        refresh.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                onDiagnostics.run();
                return true;
            }
        });
        updateAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmUpdateAll();
            }
        });
        breadcrumb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                goUp();
            }
        });
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filterClear.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                render();
            }
        });
        filterClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter.setText("");
            }
        });
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                onRowTapped(adapter.getItem(pos), view);
            }
        });
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int pos, long id) {
                RowBuilder.Row r = adapter.getItem(pos);
                if (r.menu.isEmpty())
                    return false;
                showMenu(r, view);
                return true;
            }
        });
    }

    // ----------------------------------------------------------------- render

    private void render() {
        Snapshot s = snapshot;
        if (s == null || disposed)
            return;
        Catalog c = s.catalog();
        folder = RowBuilder.existingFolder(c, folder);
        String f = filter.getText().toString().trim();
        List<RowBuilder.Row> rows = RowBuilder.build(s, tab, folder, f);

        styleTab(tabLatest, tab == RowBuilder.Tab.LATEST, false, plugin.getString(R.string.tab_latest));
        styleTab(tabBrowse, tab == RowBuilder.Tab.BROWSE, false, plugin.getString(R.string.tab_browse));
        String installed = s.records.isEmpty() ? plugin.getString(R.string.tab_installed)
                : plugin.getString(R.string.tab_installed_n, s.records.size());
        styleTab(tabInstalled, tab == RowBuilder.Tab.INSTALLED, s.updateCount > 0, installed);

        refresh.setVisibility(s.refreshing ? View.INVISIBLE : View.VISIBLE);
        refreshProgress.setVisibility(s.refreshing ? View.VISIBLE : View.GONE);
        updateAll.setVisibility(s.updateCount > 0 && tab != RowBuilder.Tab.BROWSE
                ? View.VISIBLE : View.GONE);

        boolean crumb = tab == RowBuilder.Tab.BROWSE && f.isEmpty() && !folder.isEmpty();
        breadcrumb.setVisibility(crumb ? View.VISIBLE : View.GONE);
        if (crumb) {
            breadcrumb.setText(Formats.folder(folder));
            breadcrumb.setCompoundDrawablesWithIntrinsicBounds(
                    plugin.getDrawable(R.drawable.ic_up), null, null, null);
            breadcrumb.setContentDescription(plugin.getString(R.string.up) + ": "
                    + Formats.folder(folder));
        }
        status.setText(statusText(s, f, rows.isEmpty()));
        adapter.setRows(rows);
    }

    private void styleTab(Button b, boolean active, boolean attention, String label) {
        b.setText(label);
        b.setTextColor(color(attention ? R.color.tone_attention
                : active ? R.color.heading_yellow : R.color.tab_inactive));
        b.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        b.setSelected(active);
        b.setContentDescription(active ? plugin.getString(R.string.tab_selected, label) : label);
    }

    private String statusText(Snapshot s, String f, boolean empty) {
        StringBuilder sb = new StringBuilder();
        if (s.listing == null) {
            sb.append(s.listingError != null && !s.refreshing ? s.listingError
                    : plugin.getString(R.string.status_loading));
        } else if (s.listing.notice != null) {
            sb.append(plugin.getString(R.string.status_saved, ago(s.listing.checkedAt), s.listing.notice));
        } else {
            int n = s.listing.catalog.size();
            sb.append(plugin.getResources().getQuantityString(R.plurals.status_checked, n, n,
                    ago(s.listing.checkedAt)));
        }
        if (s.listing != null && s.listing.truncated)
            sb.append('\n').append(plugin.getString(R.string.status_truncated));
        if (tab == RowBuilder.Tab.INSTALLED) {
            sb.append('\n');
            if (s.records.isEmpty())
                sb.append(plugin.getString(R.string.status_nothing_installed));
            else if (s.updateCount > 0)
                sb.append(plugin.getResources().getQuantityString(R.plurals.status_updates,
                        s.updateCount, s.updateCount));
            else if (s.listing != null)
                sb.append(plugin.getString(R.string.status_up_to_date));
        } else if (s.updateCount > 0 && tab == RowBuilder.Tab.LATEST) {
            sb.append('\n').append(plugin.getResources().getQuantityString(
                    R.plurals.status_updates, s.updateCount, s.updateCount));
        }
        if (empty && !f.isEmpty())
            sb.append('\n').append(plugin.getString(R.string.status_no_match, f));
        return sb.toString();
    }

    private String ago(long t) {
        if (t <= 0)
            return "?";
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 1)
            return plugin.getString(R.string.just_now);
        if (min < 120)
            return plugin.getString(R.string.minutes_ago, (int) min);
        return DateUtils.getRelativeTimeSpanString(t).toString();
    }

    // ---------------------------------------------------------------- actions

    private void goUp() {
        int i = folder.lastIndexOf('/');
        folder = i < 0 ? "" : folder.substring(0, i);
        render();
    }

    private void onRowTapped(RowBuilder.Row r, View anchor) {
        switch (r.type) {
            case UP:
                goUp();
                return;
            case FOLDER:
                folder = r.folder;
                render();
                return;
            case SECTION:
                return;
            default:
                break;
        }
        // tapping the row does the safe primary action; destructive ones need the menu
        if (r.primaryEnabled && r.primary != RowBuilder.Action.NONE
                && r.primary != RowBuilder.Action.REMOVE && r.primary != RowBuilder.Action.FORGET)
            perform(r, r.primary);
        else if (!r.menu.isEmpty())
            showMenu(r, anchor);
    }

    private void showMenu(final RowBuilder.Row r, View anchor) {
        PopupMenu menu = new PopupMenu(atak, anchor);
        Menu m = menu.getMenu();
        for (int i = 0; i < r.menu.size(); i++)
            m.add(Menu.NONE, i, i, actionLabel(r.menu.get(i), true));
        menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                perform(r, r.menu.get(item.getItemId()));
                return true;
            }
        });
        menu.show();
    }

    void perform(RowBuilder.Row r, RowBuilder.Action a) {
        String recordId = r.record != null ? r.record.record.id() : null;
        switch (a) {
            case INSTALL:
                confirmInstall(r.entry);
                break;
            case RETRY:
                engine.install(r.job != null ? r.job.target : r.entry);
                break;
            case UPDATE: {
                final RepoEntry target = updateTarget(r);
                if (target != null)
                    confirmSize(target, new Runnable() {
                        @Override
                        public void run() {
                            // exactly the file the user confirmed; it replaces every
                            // installed version it is the update for
                            engine.updateTo(target);
                        }
                    });
                break;
            }
            case CANCEL:
            case DISMISS:
                cancel(r);
                break;
            case VIEW:
                if (r.record != null)
                    zoomTo(r.record.record.localPath());
                break;
            case RELOAD:
            case ADD_ICONS:
                if (recordId != null)
                    engine.reload(recordId);
                break;
            case REPLACE:
                confirmReplace(r);
                break;
            case REMOVE:
                confirmRemove(r);
                break;
            case FORGET:
                confirmForget(r);
                break;
            case NONE:
                break;
        }
    }

    /** "Iran Kinetic Events (Oct 10, 2026 · v2)", so two installed versions can't be confused. */
    private static String versionedName(RepoEntry e) {
        String v = Formats.version(e.name());
        return v.isEmpty() ? e.name().getProduct() : e.name().getProduct() + " (" + v + ")";
    }

    private RepoEntry updateTarget(RowBuilder.Row r) {
        if (r.type == RowBuilder.RowType.ENTRY)
            return r.entry;
        UpdatePlanner.Status st = r.record.status;
        return st != null && st.target != null ? st.target : r.entry;
    }

    private void cancel(final RowBuilder.Row r) {
        final Snapshot.JobView j = r.job;
        if (j == null)
            return;
        if (j.phase != Snapshot.JobPhase.DOWNLOADING) {
            engine.cancel(j.target.path());
            return;
        }
        show(new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_stop_title, j.target.fileName()))
                .setMessage(plugin.getString(R.string.dlg_stop_msg))
                .setPositiveButton(plugin.getString(R.string.dlg_stop),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.cancel(j.target.path());
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_keep), null));
    }

    private void confirmInstall(final RepoEntry e) {
        Snapshot s = snapshot;
        List<String> elsewhere = s == null || e.kind() != FileKind.MAP_LAYER
                ? Collections.<String>emptyList() : s.otherSources.matching(e);
        if (elsewhere.isEmpty()) {
            confirmSize(e, installOf(e));
            return;
        }
        StringBuilder where = new StringBuilder();
        for (String p : elsewhere.subList(0, Math.min(3, elsewhere.size()))) {
            if (where.length() > 0)
                where.append('\n');
            int i = p.indexOf("/atak/");
            where.append(i >= 0 ? p.substring(i + 1) : p);
        }
        show(new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_elsewhere_title))
                .setMessage(plugin.getString(R.string.dlg_elsewhere_msg, e.name().getProduct(),
                        where.toString()))
                .setPositiveButton(plugin.getString(R.string.dlg_install_anyway),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                confirmSize(e, installOf(e));
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null));
    }

    private Runnable installOf(final RepoEntry e) {
        return new Runnable() {
            @Override
            public void run() {
                engine.install(e);
            }
        };
    }

    private boolean metered() {
        try {
            ConnectivityManager cm = (ConnectivityManager) atak
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void confirmSize(RepoEntry e, final Runnable go) {
        boolean metered = metered();
        long size = e.size();
        if (size < CONFIRM_BYTES && !(metered && size >= CONFIRM_BYTES_METERED)) {
            go.run();
            return;
        }
        String msg = plugin.getString(R.string.dlg_big_msg, e.fileName(), Formats.size(size));
        if (metered)
            msg += "\n\n" + plugin.getString(R.string.dlg_metered);
        show(new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_big_title, Formats.size(size)))
                .setMessage(msg)
                .setPositiveButton(plugin.getString(R.string.dlg_download),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                go.run();
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null));
    }

    private void confirmUpdateAll() {
        Snapshot s = snapshot;
        if (s == null)
            return;
        long total = 0;
        Set<String> seen = new HashSet<>();
        for (Snapshot.RecordView v : s.records)
            if (v.hasUpdate() && seen.add(v.status.target.path()))
                total += Math.max(0, v.status.target.size());
        if (seen.isEmpty())
            return;
        String msg = plugin.getString(R.string.dlg_update_all_msg, Formats.size(total));
        if (metered())
            msg += "\n\n" + plugin.getString(R.string.dlg_metered);
        show(new AlertDialog.Builder(atak)
                .setTitle(plugin.getResources().getQuantityString(
                        R.plurals.dlg_update_all_title, seen.size(), seen.size()))
                .setMessage(msg)
                .setPositiveButton(plugin.getString(R.string.dlg_download),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.updateAll();
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null));
    }

    private void confirmRemove(final RowBuilder.Row r) {
        if (r.record == null)
            return;
        final String id = r.record.record.id();
        boolean icons = r.record.record.kind() == FileKind.ICON_SET;
        show(new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_remove_title, versionedName(r.record.record.asEntry())))
                .setMessage(plugin.getString(icons ? R.string.dlg_remove_icons_msg
                        : R.string.dlg_remove_map_msg))
                .setPositiveButton(plugin.getString(R.string.act_remove),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.remove(id);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null));
    }

    private void confirmForget(final RowBuilder.Row r) {
        if (r.record == null)
            return;
        final String id = r.record.record.id();
        show(new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_forget_title,
                        r.record.record.asEntry().fileName()))
                .setMessage(plugin.getString(R.string.dlg_forget_msg, r.record.record.localPath()))
                .setPositiveButton(plugin.getString(R.string.dlg_forget),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                engine.forget(id);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null));
    }

    private void confirmReplace(final RowBuilder.Row r) {
        if (r.record == null || r.suggestion == null)
            return;
        final String id = r.record.record.id();
        final RepoEntry suggestion = r.suggestion;
        show(new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_replace_title))
                .setMessage(plugin.getString(R.string.dlg_replace_msg,
                        r.record.record.repoPath(), suggestion.fileName(),
                        Formats.folder(suggestion.folder())))
                .setPositiveButton(plugin.getString(R.string.dlg_replace),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                confirmSize(suggestion, new Runnable() {
                                    @Override
                                    public void run() {
                                        engine.replaceWith(id, suggestion);
                                    }
                                });
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null));
    }

    private void show(AlertDialog.Builder b) {
        if (disposed)
            return;
        final AlertDialog d = b.create();
        dialogs.add(d);
        d.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface di) {
                dialogs.remove(d);
            }
        });
        d.show();
    }

    /** Same as tapping the file in the Overlay Manager: fly the map to it. */
    private static void zoomTo(String localPath) {
        Intent i = new Intent(ImportExportMapComponent.ZOOM_TO_FILE_ACTION);
        i.putExtra("filepath", localPath);
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    // ------------------------------------------------------------------- text

    String actionLabel(RowBuilder.Action a, boolean inMenu) {
        switch (a) {
            case INSTALL:
                return plugin.getString(R.string.act_install);
            case UPDATE:
                return plugin.getString(R.string.act_update);
            case RETRY:
                return plugin.getString(R.string.act_retry);
            case CANCEL:
                return plugin.getString(R.string.act_cancel);
            case VIEW:
                return plugin.getString(inMenu ? R.string.act_view_long : R.string.act_view);
            case RELOAD:
                return plugin.getString(R.string.act_reload);
            case ADD_ICONS:
                return plugin.getString(R.string.act_add_icons);
            case REPLACE:
                return plugin.getString(R.string.act_replace);
            case REMOVE:
                return plugin.getString(R.string.act_remove);
            case FORGET:
                return plugin.getString(R.string.act_forget);
            case DISMISS:
                return plugin.getString(R.string.act_dismiss);
            default:
                return "";
        }
    }

    String statusLabel(RowBuilder.Row r) {
        String arg = r.statusArg == null ? "" : r.statusArg;
        switch (r.status) {
            case ON_MAP:
                return plugin.getString(R.string.st_on_map);
            case ICONS_INSTALLED:
                return plugin.getString(R.string.st_icons_installed);
            case LOADING:
                return plugin.getString(R.string.st_loading);
            case NOT_LOADED:
                return plugin.getString(R.string.st_not_loaded);
            case ICONS_ADDING:
                return plugin.getString(R.string.st_icons_adding);
            case ICONS_NOT_ADDED:
                return plugin.getString(R.string.st_icons_not_added);
            case REMOVING:
                return plugin.getString(R.string.st_removing);
            case UPDATE_NEWER:
                return plugin.getString(r.type == RowBuilder.RowType.RECORD
                        ? R.string.st_update_newer_target : R.string.st_update_newer, arg);
            case UPDATE_CHANGED:
                return plugin.getString(R.string.st_update_changed);
            case SUPERSEDED:
                return plugin.getString(R.string.st_superseded, arg);
            case GONE:
                return r.suggestion != null ? plugin.getString(R.string.st_gone_suggestion, arg)
                        : plugin.getString(R.string.st_gone);
            case UNMANAGED:
                return plugin.getString(R.string.st_unmanaged);
            case OTHER_VERSION_INSTALLED:
                return plugin.getString(R.string.st_other_version, arg);
            case ALSO_ELSEWHERE:
                return plugin.getString(R.string.st_also_elsewhere);
            case QUEUED:
                return plugin.getString(R.string.st_queued);
            case DOWNLOADING:
                return plugin.getString(R.string.st_downloading, arg);
            case INSTALLING:
                return plugin.getString(R.string.st_installing);
            case WAITING:
                return plugin.getString(R.string.st_waiting);
            case FAILED:
                return plugin.getString(R.string.st_failed, arg);
            default:
                return "";
        }
    }

    private int toneColor(RowBuilder.Tone t) {
        switch (t) {
            case GOOD:
                return color(R.color.tone_good);
            case ATTENTION:
                return color(R.color.tone_attention);
            case PROBLEM:
                return color(R.color.tone_problem);
            default:
                return color(R.color.tone_normal);
        }
    }

    private String detailLine(RowBuilder.Row r) {
        RepoEntry e = r.entry;
        List<String> parts = new ArrayList<>();
        if (e.kind() == FileKind.ICON_SET)
            parts.add(plugin.getString(R.string.kind_icon_set));
        String when = Formats.version(e.name());
        parts.add(when.isEmpty() ? plugin.getString(R.string.undated) : when);
        parts.add(Formats.size(e.size()));
        if (r.count > 1)
            parts.add(plugin.getResources().getQuantityString(R.plurals.detail_versions,
                    r.count, r.count));
        if (r.showFolder)
            parts.add(Formats.folderName(e.folder()));
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isEmpty())
                continue;
            if (sb.length() > 0)
                sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- adapter

    private static final class Holder {
        ImageView icon;
        TextView title;
        TextView detail;
        TextView status;
        ProgressBar progress;
        Button action;
    }

    private final class Adapter extends BaseAdapter {
        private List<RowBuilder.Row> rows = Collections.emptyList();

        void setRows(List<RowBuilder.Row> rows) {
            this.rows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public RowBuilder.Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type == RowBuilder.RowType.SECTION ? 1 : 0;
        }

        @Override
        public boolean isEnabled(int position) {
            return rows.get(position).type != RowBuilder.RowType.SECTION;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowBuilder.Row r = rows.get(position);
            LayoutInflater inf = LayoutInflater.from(plugin);
            if (r.type == RowBuilder.RowType.SECTION) {
                TextView t = convertView instanceof TextView ? (TextView) convertView
                        : (TextView) inf.inflate(R.layout.ghostmaps_section, parent, false);
                t.setText(plugin.getString(r.section == RowBuilder.Section.ICON_SETS
                        ? R.string.section_icon_sets : R.string.section_map_layers));
                return t;
            }
            Holder h;
            if (convertView == null || !(convertView.getTag() instanceof Holder)) {
                convertView = inf.inflate(R.layout.ghostmaps_row, parent, false);
                h = new Holder();
                h.icon = convertView.findViewById(R.id.row_icon);
                h.title = convertView.findViewById(R.id.row_title);
                h.detail = convertView.findViewById(R.id.row_detail);
                h.status = convertView.findViewById(R.id.row_status);
                h.progress = convertView.findViewById(R.id.row_progress);
                h.action = convertView.findViewById(R.id.row_action);
                convertView.setTag(h);
            } else {
                h = (Holder) convertView.getTag();
            }
            bind(h, r, convertView);
            return convertView;
        }
    }

    private void bind(Holder h, final RowBuilder.Row r, View rowView) {
        h.progress.setVisibility(View.GONE);
        h.status.setVisibility(View.GONE);
        h.action.setVisibility(View.GONE);
        switch (r.type) {
            case UP: {
                String parent = r.folder.contains("/")
                        ? r.folder.substring(0, r.folder.lastIndexOf('/')) : "";
                h.icon.setImageDrawable(plugin.getDrawable(R.drawable.ic_up));
                h.title.setText(parent.isEmpty() ? plugin.getString(R.string.all_folders)
                        : Formats.folderName(parent));
                h.detail.setText(plugin.getString(R.string.up));
                rowView.setContentDescription(plugin.getString(R.string.up));
                return;
            }
            case FOLDER:
                h.icon.setImageDrawable(plugin.getDrawable(R.drawable.ic_folder));
                h.title.setText(Formats.folderName(r.folder));
                h.detail.setText(plugin.getResources().getQuantityString(
                        R.plurals.files_count, r.count, r.count));
                rowView.setContentDescription(Formats.folderName(r.folder) + ", "
                        + h.detail.getText());
                return;
            default:
                break;
        }

        boolean entryRowInFile = r.type == RowBuilder.RowType.ENTRY && tab == RowBuilder.Tab.BROWSE;
        h.title.setText(entryRowInFile ? Formats.fileName(r.entry.fileName())
                : r.entry.name().getProduct());
        h.detail.setText(detailLine(r));

        String st = statusLabel(r);
        if (!st.isEmpty()) {
            h.status.setVisibility(View.VISIBLE);
            h.status.setText(st);
            h.status.setTextColor(toneColor(r.tone));
        }
        if (r.progress != -1) {
            h.progress.setVisibility(View.VISIBLE);
            h.progress.setIndeterminate(r.progress == -2);
            if (r.progress >= 0)
                h.progress.setProgress(r.progress);
        }

        int icon;
        if (r.isIconSet())
            icon = R.drawable.ic_iconset;
        else if (r.tone == RowBuilder.Tone.ATTENTION || r.tone == RowBuilder.Tone.PROBLEM)
            icon = r.record != null ? R.drawable.ic_layer_update : R.drawable.ic_layer;
        else if (r.record != null && r.record.presence == Snapshot.Presence.ACTIVE)
            icon = R.drawable.ic_layer_installed;
        else
            icon = R.drawable.ic_layer;
        h.icon.setImageDrawable(plugin.getDrawable(icon));

        if (r.primary != RowBuilder.Action.NONE) {
            h.action.setVisibility(View.VISIBLE);
            String label = r.primary == RowBuilder.Action.CANCEL && !r.primaryEnabled
                    ? plugin.getString(R.string.act_installing) : actionLabel(r.primary, false);
            h.action.setText(label);
            h.action.setEnabled(r.primaryEnabled);
            h.action.setTextColor(r.primary == RowBuilder.Action.UPDATE
                    ? color(R.color.tone_attention) : color(R.color.text_primary));
            h.action.setContentDescription(label + ": " + h.title.getText());
            h.action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    perform(r, r.primary);
                }
            });
        }
        rowView.setContentDescription(h.title.getText() + ". " + h.detail.getText()
                + (st.isEmpty() ? "" : ". " + st));
    }

    /** Color resource; Context.getColor needs API 23 and the plugin supports 21. */
    @SuppressWarnings("deprecation")
    private int color(int id) {
        return android.os.Build.VERSION.SDK_INT >= 23 ? plugin.getColor(id)
                : plugin.getResources().getColor(id);
    }
}
