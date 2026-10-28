package com.atakmap.android.ghostmaps.plugin;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.FileName;
import com.atakmap.android.ghostmaps.plugin.core.Formats;
import com.atakmap.android.ghostmaps.plugin.core.InstalledFile;
import com.atakmap.android.ghostmaps.plugin.core.InstalledStore;
import com.atakmap.android.ghostmaps.plugin.core.RepoFile;
import com.atakmap.android.ghostmaps.plugin.core.UpdateCheck;
import com.atakmap.android.importexport.ImportExportMapComponent;
import com.atakmap.android.ipc.AtakBroadcast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The GhostMaps panel: three views of the repo (Latest = newest version of
 * every product; Browse = the repo's folders; Installed = what GhostMaps put
 * on this device, with updates), a filter box and per-file Install / Update
 * / Remove buttons.
 */
final class GhostMapsPane implements GhostMapsManager.Listener {

    /** Ask before downloading anything at least this big. */
    private static final long CONFIRM_BYTES = 10L * 1024 * 1024;
    /** On cellular/hotspot, ask from this size. */
    private static final long CONFIRM_BYTES_METERED = 2L * 1024 * 1024;

    private enum Tab {
        LATEST, BROWSE, INSTALLED
    }

    private enum Kind {
        UP, FOLDER, FILE, INSTALLED
    }

    private static final class Row {
        final Kind kind;
        final String folder;
        final int count;
        final RepoFile file;
        final UpdateCheck.Status status;

        Row(Kind kind, String folder, int count, RepoFile file, UpdateCheck.Status status) {
            this.kind = kind;
            this.folder = folder;
            this.count = count;
            this.file = file;
            this.status = status;
        }
    }

    private final Context plugin;
    private final Context atak;
    private final GhostMapsManager mgr;
    private final View root;
    private final Button tabLatest, tabBrowse, tabInstalled, updateAll;
    private final ImageButton refresh, filterClear;
    private final ProgressBar refreshProgress;
    private final EditText filter;
    private final TextView status, breadcrumb;
    private final Adapter adapter = new Adapter();

    private Tab tab = Tab.LATEST;
    private String folder = "";
    private List<UpdateCheck.Status> statuses = new ArrayList<>();
    private Map<String, UpdateCheck.Status> statusByPath = new HashMap<>();

    // the pane's root has no parent view to inflate into
    @SuppressLint("InflateParams")
    GhostMapsPane(Context pluginContext, Context atakContext, GhostMapsManager mgr) {
        this.plugin = pluginContext;
        this.atak = atakContext;
        this.mgr = mgr;
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

        tabLatest.setOnClickListener(new TabClick(Tab.LATEST));
        tabBrowse.setOnClickListener(new TabClick(Tab.BROWSE));
        tabInstalled.setOnClickListener(new TabClick(Tab.INSTALLED));
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                GhostMapsPane.this.mgr.refresh(true);
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
                rebuild();
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
                onRowTapped(adapter.getItem(pos));
            }
        });

        mgr.addListener(this);
        rebuild();
    }

    View getView() {
        return root;
    }

    void onShown() {
        mgr.refresh(false);
        rebuild();
    }

    void dispose() {
        mgr.removeListener(this);
    }

    @Override
    public void onStateChanged() {
        rebuild();
    }

    // ------------------------------------------------------------ building

    private final class TabClick implements View.OnClickListener {
        private final Tab target;

        TabClick(Tab target) {
            this.target = target;
        }

        @Override
        public void onClick(View v) {
            if (tab == target && target == Tab.BROWSE)
                folder = "";
            tab = target;
            rebuild();
        }
    }

    private String filterText() {
        return filter.getText().toString().trim();
    }

    private void rebuild() {
        Catalog cat = mgr.getCatalog();
        InstalledStore store = mgr.getStore();
        statuses = mgr.getStatuses();
        statusByPath = new HashMap<>();
        for (UpdateCheck.Status s : statuses)
            statusByPath.put(s.installed.getRepoPath(), s);
        int updates = UpdateCheck.countUpdates(statuses);

        styleTab(tabLatest, tab == Tab.LATEST, false);
        styleTab(tabBrowse, tab == Tab.BROWSE, false);
        styleTab(tabInstalled, tab == Tab.INSTALLED, updates > 0);
        tabInstalled.setText(store.size() > 0
                ? plugin.getString(R.string.tab_installed_n, store.size())
                : plugin.getString(R.string.tab_installed));

        boolean busy = mgr.isRefreshing();
        refresh.setVisibility(busy ? View.INVISIBLE : View.VISIBLE);
        refreshProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
        updateAll.setVisibility(updates > 0 && tab != Tab.BROWSE ? View.VISIBLE : View.GONE);

        String f = filterText();
        List<Row> rows = new ArrayList<>();
        if (cat != null) {
            switch (tab) {
                case LATEST:
                    for (RepoFile r : cat.latest(f))
                        rows.add(new Row(Kind.FILE, null, 0, r, null));
                    break;
                case BROWSE:
                    if (!f.isEmpty()) {
                        for (RepoFile r : cat.search(f))
                            rows.add(new Row(Kind.FILE, null, 0, r, null));
                    } else {
                        // folder renamed or removed on GitHub: back up to
                        // the nearest one that still exists
                        while (!folder.isEmpty() && !cat.hasFolder(folder))
                            folder = parent(folder);
                        if (!folder.isEmpty())
                            rows.add(new Row(Kind.UP, folder, 0, null, null));
                        for (Map.Entry<String, Integer> e : cat.subfolders(folder).entrySet())
                            rows.add(new Row(Kind.FOLDER, e.getKey(), e.getValue(), null, null));
                        for (RepoFile r : cat.filesIn(folder))
                            rows.add(new Row(Kind.FILE, null, 0, r, null));
                    }
                    break;
                case INSTALLED:
                    break;
            }
        }
        if (tab == Tab.INSTALLED) {
            List<UpdateCheck.Status> sorted = new ArrayList<>(statuses);
            if (cat == null) {
                for (InstalledFile i : store.all())
                    sorted.add(new UpdateCheckStub(i).status);
            }
            Collections.sort(sorted, INSTALLED_ORDER);
            for (UpdateCheck.Status s : sorted)
                if (matches(s.installed.getRepoPath(), f))
                    rows.add(new Row(Kind.INSTALLED, null, 0, null, s));
        }

        boolean showCrumb = tab == Tab.BROWSE && f.isEmpty() && !folder.isEmpty();
        breadcrumb.setVisibility(showCrumb ? View.VISIBLE : View.GONE);
        if (showCrumb) {
            breadcrumb.setText(Formats.folder(folder));
            breadcrumb.setCompoundDrawablesWithIntrinsicBounds(
                    plugin.getDrawable(R.drawable.ic_up), null, null, null);
            breadcrumb.setContentDescription(plugin.getString(R.string.up));
        }

        status.setText(statusText(cat, updates, f, rows.isEmpty()));
        adapter.setRows(rows);
    }

    /** Lets the Installed tab list files before any repo listing exists. */
    private static final class UpdateCheckStub {
        final UpdateCheck.Status status;

        UpdateCheckStub(InstalledFile i) {
            status = UpdateCheck.check(i, new Catalog(Collections.singletonList(i.asRepoFile())), null);
        }
    }

    private static final Comparator<UpdateCheck.Status> INSTALLED_ORDER =
            new Comparator<UpdateCheck.Status>() {
                @Override
                public int compare(UpdateCheck.Status a, UpdateCheck.Status b) {
                    int ra = a.hasUpdate() ? 0 : 1;
                    int rb = b.hasUpdate() ? 0 : 1;
                    if (ra != rb)
                        return ra - rb;
                    return Catalog.NEWEST_FIRST.compare(a.installed.asRepoFile(),
                            b.installed.asRepoFile());
                }
            };

    private String statusText(Catalog cat, int updates, String f, boolean empty) {
        GitHubSource.Listing l = mgr.getListing();
        StringBuilder sb = new StringBuilder();
        if (l == null) {
            if (mgr.isRefreshing() || mgr.getListingError() == null)
                sb.append(plugin.getString(R.string.status_loading));
            else
                sb.append(plugin.getString(R.string.status_no_list, mgr.getListingError()));
        } else if (l.notice != null) {
            sb.append(plugin.getString(R.string.status_saved, ago(l.checkedAt), l.notice + "."));
        } else {
            sb.append(plugin.getResources().getQuantityString(R.plurals.status_checked,
                    cat.size(), cat.size(), ago(l.checkedAt)));
        }
        if (l != null && l.truncated)
            sb.append('\n').append(plugin.getString(R.string.status_truncated));

        if (tab == Tab.INSTALLED) {
            sb.append('\n');
            if (mgr.getStore().size() == 0)
                sb.append(plugin.getString(R.string.status_nothing_installed));
            else if (updates > 0)
                sb.append(plugin.getResources().getQuantityString(
                        R.plurals.status_updates, updates, updates));
            else if (cat != null)
                sb.append(plugin.getString(R.string.status_up_to_date));
        } else if (updates > 0 && tab == Tab.LATEST) {
            sb.append('\n').append(plugin.getResources().getQuantityString(
                    R.plurals.status_updates, updates, updates));
        }
        if (empty && !f.isEmpty())
            sb.append('\n').append(plugin.getString(R.string.status_no_match, f));
        return sb.toString();
    }

    private String ago(long t) {
        long min = (System.currentTimeMillis() - t) / 60000;
        if (min < 1)
            return plugin.getString(R.string.just_now);
        if (min < 120)
            return plugin.getString(R.string.minutes_ago, (int) min);
        return android.text.format.DateUtils.getRelativeTimeSpanString(t).toString();
    }

    private void styleTab(Button b, boolean active, boolean attention) {
        int color = attention ? Color.rgb(0xF0, 0xA0, 0x30)
                : active ? Color.rgb(0xDF, 0xB2, 0x28)
                        : Color.rgb(0xC8, 0xC8, 0xC8);
        b.setTextColor(color);
        b.setTypeface(null, active ? Typeface.BOLD : Typeface.NORMAL);
        b.setSelected(active);
    }

    private static boolean matches(String path, String f) {
        if (f.isEmpty())
            return true;
        String hay = path.replace('_', ' ').toLowerCase(java.util.Locale.US);
        for (String w : f.toLowerCase(java.util.Locale.US).split("\\s+"))
            if (!hay.contains(w))
                return false;
        return true;
    }

    private static String parent(String folder) {
        int i = folder.lastIndexOf('/');
        return i < 0 ? "" : folder.substring(0, i);
    }

    // ------------------------------------------------------------ actions

    private void goUp() {
        folder = parent(folder);
        rebuild();
    }

    private void onRowTapped(Row row) {
        switch (row.kind) {
            case UP:
                goUp();
                break;
            case FOLDER:
                folder = row.folder;
                rebuild();
                break;
            case FILE: {
                InstalledFile inst = mgr.getStore().get(row.file.getPath());
                if (inst != null)
                    zoomTo(inst);
                else
                    onAction(row);
                break;
            }
            case INSTALLED:
                zoomTo(row.status.installed);
                break;
        }
    }

    /** What the row's button does and says. */
    private enum Action {
        NONE, INSTALL, UPDATE, INSTALLED, REMOVE, RETRY, QUEUED, DOWNLOADING, IMPORTING
    }

    private Action actionFor(Row row) {
        RepoFile target = targetOf(row);
        GhostMapsManager.Job job = target == null ? null : mgr.getJob(target.getPath());
        if (job != null) {
            switch (job.phase) {
                case QUEUED:
                    return Action.QUEUED;
                case DOWNLOADING:
                    return Action.DOWNLOADING;
                case IMPORTING:
                    return Action.IMPORTING;
                case FAILED:
                    return Action.RETRY;
            }
        }
        if (row.kind == Kind.INSTALLED)
            return row.status.hasUpdate() ? Action.UPDATE : Action.REMOVE;
        if (row.kind != Kind.FILE)
            return Action.NONE;
        InstalledStore store = mgr.getStore();
        InstalledFile inst = store.get(row.file.getPath());
        if (inst != null)
            return inst.getSha().equals(row.file.getSha()) ? Action.INSTALLED : Action.UPDATE;
        if (tab == Tab.LATEST && olderInstalled(row.file) != null)
            return Action.UPDATE;
        return Action.INSTALL;
    }

    /** The repo file a row's download would fetch. */
    private static RepoFile targetOf(Row row) {
        if (row.kind == Kind.FILE)
            return row.file;
        if (row.kind == Kind.INSTALLED)
            return row.status.hasUpdate() ? row.status.target : row.status.installed.asRepoFile();
        return null;
    }

    private InstalledFile olderInstalled(RepoFile f) {
        InstalledFile best = null;
        for (InstalledFile i : mgr.getStore().all()) {
            RepoFile r = i.asRepoFile();
            if (r.productKey().equals(f.productKey())
                    && r.parsed().compareAge(f.parsed()) < 0
                    && (best == null || r.parsed().compareAge(best.asRepoFile().parsed()) > 0))
                best = i;
        }
        return best;
    }

    private void onAction(Row row) {
        final RepoFile target = targetOf(row);
        switch (actionFor(row)) {
            case INSTALL:
                confirmThenInstall(target, false);
                break;
            case UPDATE:
                confirmThenInstall(target, true);
                break;
            case RETRY: {
                GhostMapsManager.Job j = mgr.getJob(target.getPath());
                confirmThenInstall(target, j != null && j.replaceOlder);
                break;
            }
            case INSTALLED:
                confirmRemove(mgr.getStore().get(row.file.getPath()));
                break;
            case REMOVE:
                confirmRemove(row.status.installed);
                break;
            case QUEUED:
                mgr.cancel(target.getPath());
                break;
            case DOWNLOADING:
                confirmCancel(target);
                break;
            default:
                break;
        }
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

    /**
     * Before installing: point out a copy already loaded from another source
     * (DataSync etc.), then confirm large downloads.
     */
    private void confirmThenInstall(final RepoFile f, final boolean replaceOlder) {
        List<String> elsewhere = mgr.getOtherSources().forProduct(f);
        if (elsewhere.isEmpty() || hasInstalledVersion(f)) {
            confirmSizeThenInstall(f, replaceOlder);
            return;
        }
        StringBuilder where = new StringBuilder();
        for (String p : elsewhere.subList(0, Math.min(3, elsewhere.size()))) {
            if (where.length() > 0)
                where.append('\n');
            where.append(shortPath(p));
        }
        new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_elsewhere_title))
                .setMessage(plugin.getString(R.string.dlg_elsewhere_msg,
                        f.parsed().getProduct(), where.toString()))
                .setPositiveButton(plugin.getString(R.string.dlg_install_anyway),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                confirmSizeThenInstall(f, replaceOlder);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null)
                .show();
    }

    private boolean hasInstalledVersion(RepoFile f) {
        for (InstalledFile i : mgr.getStore().all())
            if (i.asRepoFile().productKey().equals(f.productKey()))
                return true;
        return false;
    }

    /** "/storage/emulated/0/atak/tools/x/y.kmz" → "atak/tools/x/y.kmz". */
    private static String shortPath(String p) {
        int i = p.indexOf("/atak/");
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private void confirmSizeThenInstall(final RepoFile f, final boolean replaceOlder) {
        boolean metered = metered();
        long size = f.getSize();
        if (size < CONFIRM_BYTES && !(metered && size >= CONFIRM_BYTES_METERED)) {
            mgr.install(f, replaceOlder);
            return;
        }
        String msg = plugin.getString(R.string.dlg_big_msg, f.fileName(), Formats.size(size));
        if (metered)
            msg += "\n\n" + plugin.getString(R.string.dlg_metered);
        new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_big_title, Formats.size(size)))
                .setMessage(msg)
                .setPositiveButton(plugin.getString(R.string.dlg_download),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                mgr.install(f, replaceOlder);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null)
                .show();
    }

    private void confirmUpdateAll() {
        long total = 0;
        List<String> seen = new ArrayList<>();
        for (UpdateCheck.Status s : statuses)
            if (s.hasUpdate() && !seen.contains(s.target.getPath())) {
                seen.add(s.target.getPath());
                total += Math.max(0, s.target.getSize());
            }
        if (seen.isEmpty())
            return;
        String msg = plugin.getString(R.string.dlg_update_all_msg, Formats.size(total));
        if (metered())
            msg += "\n\n" + plugin.getString(R.string.dlg_metered);
        new AlertDialog.Builder(atak)
                .setTitle(plugin.getResources().getQuantityString(
                        R.plurals.dlg_update_all_title, seen.size(), seen.size()))
                .setMessage(msg)
                .setPositiveButton(plugin.getString(R.string.dlg_download),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                mgr.updateAll();
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null)
                .show();
    }

    private void confirmRemove(final InstalledFile inst) {
        if (inst == null)
            return;
        new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_remove_title,
                        new File(inst.getRepoPath()).getName()))
                .setMessage(plugin.getString(R.string.dlg_remove_msg))
                .setPositiveButton(plugin.getString(R.string.act_remove),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                mgr.remove(inst);
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_cancel), null)
                .show();
    }

    private void confirmCancel(final RepoFile f) {
        new AlertDialog.Builder(atak)
                .setTitle(plugin.getString(R.string.dlg_cancel_dl_title, f.fileName()))
                .setMessage(plugin.getString(R.string.dlg_cancel_dl_msg))
                .setPositiveButton(plugin.getString(R.string.dlg_stop),
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                mgr.cancel(f.getPath());
                            }
                        })
                .setNegativeButton(plugin.getString(R.string.dlg_keep), null)
                .show();
    }

    /** Same as tapping the file in Overlay Manager: fly the map to it. */
    private static void zoomTo(InstalledFile inst) {
        Intent i = new Intent(ImportExportMapComponent.ZOOM_TO_FILE_ACTION);
        i.putExtra("filepath", inst.getLocalPath());
        AtakBroadcast.getInstance().sendBroadcast(i);
    }

    // ------------------------------------------------------------ adapter

    private static final class Holder {
        ImageView icon;
        TextView title, detail;
        ProgressBar progress;
        Button action;
    }

    private final class Adapter extends BaseAdapter {
        private List<Row> rows = new ArrayList<>();

        void setRows(List<Row> rows) {
            this.rows = rows;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Row getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Holder h;
            if (convertView == null) {
                convertView = LayoutInflater.from(plugin).inflate(R.layout.ghostmaps_row, parent, false);
                h = new Holder();
                h.icon = convertView.findViewById(R.id.row_icon);
                h.title = convertView.findViewById(R.id.row_title);
                h.detail = convertView.findViewById(R.id.row_detail);
                h.progress = convertView.findViewById(R.id.row_progress);
                h.action = convertView.findViewById(R.id.row_action);
                convertView.setTag(h);
            } else {
                h = (Holder) convertView.getTag();
            }
            bind(h, rows.get(position));
            return convertView;
        }
    }

    private void bind(Holder h, final Row row) {
        h.progress.setVisibility(View.GONE);
        h.detail.setTextColor(Color.rgb(0xA0, 0xA0, 0xA0));
        switch (row.kind) {
            case UP:
                h.icon.setImageDrawable(plugin.getDrawable(R.drawable.ic_up));
                h.title.setText(Formats.folderName(parent(row.folder)).isEmpty()
                        ? plugin.getString(R.string.all_folders)
                        : Formats.folderName(parent(row.folder)));
                h.detail.setText(plugin.getString(R.string.up));
                h.action.setVisibility(View.GONE);
                return;
            case FOLDER:
                h.icon.setImageDrawable(plugin.getDrawable(R.drawable.ic_folder));
                h.title.setText(Formats.folderName(row.folder));
                h.detail.setText(plugin.getResources().getQuantityString(
                        R.plurals.files_count, row.count, row.count));
                h.action.setVisibility(View.GONE);
                return;
            default:
                break;
        }

        Action act = actionFor(row);
        RepoFile shown = row.kind == Kind.FILE ? row.file : row.status.installed.asRepoFile();
        FileName n = shown.parsed();

        // title
        if (row.kind == Kind.FILE && tab != Tab.LATEST)
            h.title.setText(Formats.fileName(shown.fileName()));
        else
            h.title.setText(n.getProduct());

        // detail
        List<String> parts = new ArrayList<>();
        String when = Formats.version(n);
        parts.add(when.isEmpty() ? plugin.getString(R.string.undated) : when);
        parts.add(Formats.size(shown.getSize()));
        if (row.kind == Kind.FILE) {
            Catalog cat = mgr.getCatalog();
            if (tab == Tab.LATEST && cat != null) {
                int versions = cat.versionsOf(shown).size();
                if (versions > 1)
                    parts.add(plugin.getResources().getQuantityString(
                            R.plurals.detail_versions, versions, versions));
            }
            if (tab != Tab.BROWSE || !filterText().isEmpty())
                parts.add(Formats.folderName(shown.folder()));
            if (!hasInstalledVersion(shown)
                    && !mgr.getOtherSources().forProduct(shown).isEmpty())
                parts.add(plugin.getString(R.string.detail_elsewhere));
            if (act == Action.UPDATE) {
                InstalledFile older = olderInstalled(shown);
                if (mgr.getStore().get(shown.getPath()) != null)
                    parts.add(plugin.getString(R.string.detail_changed));
                else if (older != null)
                    parts.add(plugin.getString(R.string.detail_installed_older,
                            dateOrName(older.asRepoFile())));
            }
        } else {
            UpdateCheck.Status s = row.status;
            switch (s.kind) {
                case NEWER_VERSION:
                    parts.add(plugin.getString(R.string.detail_newer, dateOrName(s.target)));
                    break;
                case CHANGED:
                    parts.add(plugin.getString(R.string.detail_changed));
                    break;
                case SUPERSEDED:
                    parts.add(plugin.getString(R.string.detail_superseded));
                    break;
                case REMOVED_FROM_REPO:
                    if (mgr.getCatalog() != null)
                        parts.add(plugin.getString(R.string.detail_removed));
                    break;
                case MOVED:
                    parts.add(plugin.getString(R.string.detail_moved));
                    break;
                default:
                    parts.add(Formats.folderName(shown.folder()));
                    break;
            }
        }
        RepoFile target = targetOf(row);
        GhostMapsManager.Job job = target == null ? null : mgr.getJob(target.getPath());
        InstalledFile rec = row.kind == Kind.INSTALLED ? row.status.installed
                : mgr.getStore().get(shown.getPath());
        if (job == null && rec != null && !rec.getContentType().isEmpty()
                && !Installer.isLoaded(new File(rec.getLocalPath()))) {
            // recorded, but ATAK doesn't list it (still parsing, or lost)
            parts.add(0, plugin.getString(R.string.detail_not_loaded));
            h.detail.setTextColor(Color.rgb(0xF0, 0xA0, 0x30));
        }
        if (job != null && job.phase == GhostMapsManager.Phase.FAILED) {
            h.detail.setTextColor(Color.rgb(0xFF, 0x70, 0x70));
            h.detail.setText(plugin.getString(R.string.detail_failed, job.error));
        } else {
            h.detail.setText(join(parts));
        }
        if (job != null && (job.phase == GhostMapsManager.Phase.DOWNLOADING
                || job.phase == GhostMapsManager.Phase.IMPORTING)) {
            h.progress.setVisibility(View.VISIBLE);
            h.progress.setIndeterminate(job.phase == GhostMapsManager.Phase.IMPORTING);
            h.progress.setProgress(job.percent());
        }

        // icon
        boolean installed = mgr.getStore().get(shown.getPath()) != null;
        int icon = act == Action.UPDATE ? R.drawable.ic_layer_update
                : installed ? R.drawable.ic_layer_installed : R.drawable.ic_layer;
        h.icon.setImageDrawable(plugin.getDrawable(icon));

        // button
        String label;
        boolean enabled = true;
        switch (act) {
            case INSTALL:
                label = plugin.getString(R.string.act_install);
                break;
            case UPDATE:
                label = plugin.getString(R.string.act_update);
                break;
            case INSTALLED:
                label = plugin.getString(R.string.act_installed);
                break;
            case REMOVE:
                label = plugin.getString(R.string.act_remove);
                break;
            case RETRY:
                label = plugin.getString(R.string.act_retry);
                break;
            case QUEUED:
                label = plugin.getString(R.string.act_queued);
                break;
            case DOWNLOADING:
                label = plugin.getString(R.string.act_percent, job == null ? 0 : job.percent());
                break;
            case IMPORTING:
                label = plugin.getString(R.string.act_importing);
                enabled = false;
                break;
            default:
                label = null;
                break;
        }
        if (label == null) {
            h.action.setVisibility(View.GONE);
        } else {
            h.action.setVisibility(View.VISIBLE);
            h.action.setText(label);
            h.action.setEnabled(enabled);
            h.action.setTextColor(act == Action.UPDATE ? Color.rgb(0xF0, 0xA0, 0x30)
                    : act == Action.INSTALLED ? Color.rgb(0x5C, 0xC8, 0x5C)
                            : Color.WHITE);
            h.action.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onAction(row);
                }
            });
        }
    }

    private String dateOrName(RepoFile f) {
        String d = Formats.version(f.parsed());
        return d.isEmpty() ? f.fileName() : d;
    }

    private static String join(List<String> parts) {
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
}
