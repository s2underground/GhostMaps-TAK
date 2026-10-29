package com.atakmap.android.ghostmaps.plugin.ui;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.FileKind;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.core.UpdatePlanner;
import com.atakmap.android.ghostmaps.plugin.engine.Snapshot;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a {@link Snapshot} plus what the user is looking at (tab, folder,
 * filter) into rows: what each row says and which actions it offers. Plain
 * Java with no Android or ATAK calls, so every state the panel can show is
 * unit-tested; the pane only maps these values to text and views.
 */
public final class RowBuilder {

    public enum Tab {
        LATEST, BROWSE, INSTALLED
    }

    public enum RowType {
        SECTION, UP, FOLDER, ENTRY, RECORD
    }

    /** Which section header a SECTION row is. */
    public enum Section {
        MAP_LAYERS, ICON_SETS
    }

    /** What the row's status line says (mapped to text by the pane). */
    public enum Status {
        NONE,
        // installed
        ON_MAP, ICONS_INSTALLED, LOADING, ICONS_ADDING, NOT_LOADED, ICONS_NOT_ADDED, REMOVING,
        UPDATE_NEWER, UPDATE_CHANGED, SUPERSEDED, GONE, UNMANAGED,
        // this entry vs. what's installed
        OTHER_VERSION_INSTALLED, ALSO_ELSEWHERE,
        // jobs
        QUEUED, DOWNLOADING, INSTALLING, WAITING, FAILED
    }

    public enum Tone {
        NORMAL, GOOD, ATTENTION, PROBLEM
    }

    public enum Action {
        NONE, INSTALL, UPDATE, RETRY, CANCEL, VIEW, RELOAD, ADD_ICONS, REPLACE, REMOVE, FORGET, DISMISS
    }

    public static final class Row {
        public final RowType type;
        public final Section section;
        /** FOLDER / UP: the folder path. */
        public final String folder;
        /** FOLDER: files under it. ENTRY in Latest: versions of the product. */
        public final int count;
        /** ENTRY: the repository file. RECORD: the installed version as an entry. */
        public final RepoEntry entry;
        /** The installed record this row is about, if any. */
        public final Snapshot.RecordView record;
        public final Snapshot.JobView job;
        public final Status status;
        /** Extra detail for the status: the newer date, an error, a suggested file… */
        public final String statusArg;
        /** For REPLACE: the suggested file. */
        public final RepoEntry suggestion;
        public final Tone tone;
        /** The button's action; NONE hides it. */
        public final Action primary;
        /** False while the action can't be taken (e.g. installing). */
        public final boolean primaryEnabled;
        /** Actions for the row's menu (long-press). */
        public final List<Action> menu;
        /** -1 none, -2 indeterminate, 0–100 percent. */
        public final int progress;
        /** True when the row should show the folder name (Latest, search). */
        public final boolean showFolder;

        Row(RowType type, Section section, String folder, int count, RepoEntry entry,
                Snapshot.RecordView record, Snapshot.JobView job, Status status, String statusArg,
                RepoEntry suggestion, Tone tone, Action primary, boolean primaryEnabled,
                List<Action> menu, int progress, boolean showFolder) {
            this.type = type;
            this.section = section;
            this.folder = folder;
            this.count = count;
            this.entry = entry;
            this.record = record;
            this.job = job;
            this.status = status;
            this.statusArg = statusArg;
            this.suggestion = suggestion;
            this.tone = tone;
            this.primary = primary;
            this.primaryEnabled = primaryEnabled;
            this.menu = Collections.unmodifiableList(menu);
            this.progress = progress;
            this.showFolder = showFolder;
        }

        public boolean isIconSet() {
            return entry != null && entry.kind() == FileKind.ICON_SET;
        }
    }

    /** Status + actions for one row, gathered before the Row is built. */
    private static final class Decision {
        Status status = Status.NONE;
        String arg;
        RepoEntry suggestion;
        Tone tone = Tone.NORMAL;
        Action primary = Action.NONE;
        boolean enabled = true;
        final List<Action> menu = new ArrayList<>();
        int progress = -1;
    }

    private RowBuilder() {
    }

    public static List<Row> build(Snapshot s, Tab tab, String folder, String filter) {
        List<Row> rows = new ArrayList<>();
        Catalog c = s.catalog();
        String f = filter == null ? "" : filter.trim();
        switch (tab) {
            case LATEST:
                if (c != null)
                    for (RepoEntry e : c.latest(f))
                        rows.add(entryRow(s, e, c.versionsOf(e).size(), true));
                break;
            case BROWSE:
                if (c == null)
                    break;
                if (!f.isEmpty()) {
                    for (RepoEntry e : c.search(f))
                        rows.add(entryRow(s, e, 0, true));
                    break;
                }
                if (!folder.isEmpty())
                    rows.add(simple(RowType.UP, folder, 0));
                for (Map.Entry<String, Integer> sub : c.subfolders(folder).entrySet())
                    rows.add(simple(RowType.FOLDER, sub.getKey(), sub.getValue()));
                for (RepoEntry e : c.filesIn(folder))
                    rows.add(entryRow(s, e, 0, false));
                break;
            case INSTALLED:
                addInstalled(rows, s, f);
                break;
        }
        return rows;
    }

    /** Nearest folder that still exists (folders can vanish after a refresh). */
    public static String existingFolder(Catalog c, String folder) {
        String f = folder;
        while (c != null && !f.isEmpty() && !c.hasFolder(f)) {
            int i = f.lastIndexOf('/');
            f = i < 0 ? "" : f.substring(0, i);
        }
        return f;
    }

    // ------------------------------------------------------------ entry rows

    static Row entryRow(Snapshot s, RepoEntry e, int versions, boolean showFolder) {
        Decision d = new Decision();
        Snapshot.JobView job = s.jobFor(e.path());
        Snapshot.RecordView rec = s.recordFor(e);
        if (job != null) {
            describeJob(d, job);
        } else if (rec != null && rec.record.isManaged()) {
            if (!rec.record.sha().equals(e.sha())) {
                // this entry is a changed version of what's installed at this path
                d.status = Status.UPDATE_CHANGED;
                d.tone = Tone.ATTENTION;
                d.primary = Action.UPDATE;
                d.menu.add(Action.REMOVE);
            } else {
                describePresence(d, rec);
            }
        } else if (rec != null) {
            d.status = Status.UNMANAGED;
            d.tone = Tone.ATTENTION;
            d.primary = Action.FORGET;
        } else {
            Snapshot.RecordView other = s.otherVersionOf(e);
            if (other != null && other.hasUpdate() && other.status.target != null
                    && other.status.target.path().equals(e.path())) {
                d.status = Status.UPDATE_NEWER;
                d.arg = dateOrName(other.record.asEntry());
                d.tone = Tone.ATTENTION;
                d.primary = Action.UPDATE;
                return row(RowType.ENTRY, null, e, other, null, d, versions, showFolder);
            }
            if (other != null) {
                d.status = Status.OTHER_VERSION_INSTALLED;
                d.arg = dateOrName(other.record.asEntry());
            } else if (e.kind() == FileKind.MAP_LAYER && !s.otherSources.matching(e).isEmpty()) {
                d.status = Status.ALSO_ELSEWHERE;
                d.tone = Tone.ATTENTION;
            }
            d.primary = Action.INSTALL;
        }
        return row(RowType.ENTRY, null, e, rec, job, d, versions, showFolder);
    }

    // ----------------------------------------------------------- installed tab

    private static void addInstalled(List<Row> rows, Snapshot s, String filter) {
        List<Snapshot.RecordView> maps = new ArrayList<>();
        List<Snapshot.RecordView> icons = new ArrayList<>();
        String[] words = filter.isEmpty() ? new String[0]
                : filter.toLowerCase(Locale.US).split("\\s+");
        for (Snapshot.RecordView v : s.records) {
            if (!matches(v.record.repoPath(), words))
                continue;
            (v.record.kind() == FileKind.ICON_SET ? icons : maps).add(v);
        }
        Comparator<Snapshot.RecordView> order = new Comparator<Snapshot.RecordView>() {
            @Override
            public int compare(Snapshot.RecordView a, Snapshot.RecordView b) {
                int ra = rank(a), rb = rank(b);
                if (ra != rb)
                    return ra - rb;
                return Catalog.NEWEST_FIRST.compare(a.record.asEntry(), b.record.asEntry());
            }
        };
        Collections.sort(maps, order);
        Collections.sort(icons, order);
        boolean both = !maps.isEmpty() && !icons.isEmpty();
        if (both)
            rows.add(section(Section.MAP_LAYERS));
        for (Snapshot.RecordView v : maps)
            rows.add(recordRow(s, v));
        if (both || !icons.isEmpty())
            rows.add(section(Section.ICON_SETS));
        for (Snapshot.RecordView v : icons)
            rows.add(recordRow(s, v));
    }

    /** Things needing attention first. */
    private static int rank(Snapshot.RecordView v) {
        if (v.hasUpdate())
            return 0;
        if (v.presence == Snapshot.Presence.NOT_LOADED || v.presence == Snapshot.Presence.UNMANAGED
                || (v.status != null && (v.status.kind == UpdatePlanner.Kind.GONE
                        || v.status.kind == UpdatePlanner.Kind.SUPERSEDED)))
            return 1;
        return 2;
    }

    static Row recordRow(Snapshot s, Snapshot.RecordView v) {
        Decision d = new Decision();
        RepoEntry shown = v.record.asEntry();
        Snapshot.JobView job = jobAffecting(s, v);
        if (job != null) {
            describeJob(d, job);
        } else if (v.presence == Snapshot.Presence.UNMANAGED) {
            d.status = Status.UNMANAGED;
            d.tone = Tone.ATTENTION;
            d.primary = Action.FORGET;
        } else if (v.presence == Snapshot.Presence.REMOVING) {
            d.status = Status.REMOVING;
            d.progress = -2;
        } else if (v.hasUpdate()) {
            d.status = v.status.kind == UpdatePlanner.Kind.CHANGED ? Status.UPDATE_CHANGED
                    : Status.UPDATE_NEWER;
            d.arg = dateOrName(v.status.target);
            d.tone = Tone.ATTENTION;
            d.primary = Action.UPDATE;
            addViewIfActive(d, v);
            d.menu.add(Action.REMOVE);
        } else if (v.status != null && v.status.kind == UpdatePlanner.Kind.SUPERSEDED) {
            d.status = Status.SUPERSEDED;
            d.arg = dateOrName(v.status.target);
            d.tone = Tone.ATTENTION;
            d.primary = Action.REMOVE;
            addViewIfActive(d, v);
        } else if (v.status != null && v.status.kind == UpdatePlanner.Kind.GONE) {
            d.status = Status.GONE;
            d.tone = Tone.ATTENTION;
            if (v.status.suggestion != null) {
                d.suggestion = v.status.suggestion;
                d.arg = v.status.suggestion.fileName();
                d.primary = Action.REPLACE;
            }
            addViewIfActive(d, v);
            d.menu.add(Action.REMOVE);
        } else {
            describePresence(d, v);
        }
        return row(RowType.RECORD, null, shown, v, job, d, 0, true);
    }

    /**
     * A job about this record: its own install still running, or the update
     * or replacement that will supersede it. While one runs the row shows its
     * progress and offers nothing destructive.
     */
    private static Snapshot.JobView jobAffecting(Snapshot s, Snapshot.RecordView v) {
        Snapshot.JobView own = s.jobFor(v.record.repoPath());
        if (own != null && (own.phase != Snapshot.JobPhase.FAILED || v.hasUpdate()))
            return own;
        if (v.status == null)
            return null;
        RepoEntry next = v.status.hasUpdate() ? v.status.target : v.status.suggestion;
        return next == null ? null : s.jobFor(next.path());
    }

    // ---------------------------------------------------------------- shared

    private static void describeJob(Decision d, Snapshot.JobView job) {
        switch (job.phase) {
            case QUEUED:
                d.status = Status.QUEUED;
                d.primary = Action.CANCEL;
                break;
            case DOWNLOADING:
                d.status = Status.DOWNLOADING;
                d.arg = job.percent + "%";
                d.progress = job.percent;
                d.primary = Action.CANCEL;
                break;
            case INSTALLING:
                d.status = Status.INSTALLING;
                d.progress = -2;
                d.primary = Action.CANCEL;
                d.enabled = false;
                break;
            case WAITING:
                d.status = job.target.kind() == FileKind.ICON_SET ? Status.ICONS_ADDING
                        : Status.WAITING;
                d.progress = -2;
                d.primary = Action.CANCEL;
                d.enabled = false;
                break;
            case FAILED:
                d.status = Status.FAILED;
                d.arg = job.error;
                d.tone = Tone.PROBLEM;
                d.primary = Action.RETRY;
                d.menu.add(Action.DISMISS);
                break;
        }
    }

    private static void describePresence(Decision d, Snapshot.RecordView v) {
        boolean icon = v.record.kind() == FileKind.ICON_SET;
        switch (v.presence) {
            case ACTIVE:
                d.status = icon ? Status.ICONS_INSTALLED : Status.ON_MAP;
                d.tone = Tone.GOOD;
                d.primary = icon ? Action.NONE : Action.VIEW;
                d.menu.add(Action.REMOVE);
                break;
            case LOADING:
                d.status = icon ? Status.ICONS_ADDING : Status.LOADING;
                d.progress = -2;
                d.menu.add(Action.REMOVE);
                break;
            case NOT_LOADED:
                d.status = icon ? Status.ICONS_NOT_ADDED : Status.NOT_LOADED;
                d.tone = Tone.ATTENTION;
                d.primary = icon ? Action.ADD_ICONS : Action.RELOAD;
                d.menu.add(Action.REMOVE);
                break;
            case REMOVING:
                d.status = Status.REMOVING;
                d.progress = -2;
                break;
            case UNMANAGED:
                d.status = Status.UNMANAGED;
                d.tone = Tone.ATTENTION;
                d.primary = Action.FORGET;
                break;
        }
    }

    private static void addViewIfActive(Decision d, Snapshot.RecordView v) {
        if (v.presence == Snapshot.Presence.ACTIVE && v.record.kind() == FileKind.MAP_LAYER)
            d.menu.add(Action.VIEW);
    }

    private static Row row(RowType type, Section section, RepoEntry e, Snapshot.RecordView rec,
            Snapshot.JobView job, Decision d, int count, boolean showFolder) {
        List<Action> menu = new ArrayList<>(d.menu);
        if (d.primary != Action.NONE && d.enabled && !menu.contains(d.primary))
            menu.add(0, d.primary);
        return new Row(type, section, null, count, e, rec, job, d.status, d.arg, d.suggestion,
                d.tone, d.primary, d.enabled, menu, d.progress, showFolder);
    }

    private static Row simple(RowType type, String folder, int count) {
        return new Row(type, null, folder, count, null, null, null, Status.NONE, null, null,
                Tone.NORMAL, Action.NONE, true, Collections.<Action>emptyList(), -1, false);
    }

    private static Row section(Section s) {
        return new Row(RowType.SECTION, s, null, 0, null, null, null, Status.NONE, null, null,
                Tone.NORMAL, Action.NONE, true, Collections.<Action>emptyList(), -1, false);
    }

    /** "Oct 27, 2026" or "v5", else the file name. */
    static String dateOrName(RepoEntry e) {
        String v = com.atakmap.android.ghostmaps.plugin.core.Formats.version(e.name());
        return v.isEmpty() ? e.fileName() : v;
    }

    private static boolean matches(String path, String[] words) {
        if (words.length == 0)
            return true;
        String hay = path.replace('_', ' ').toLowerCase(Locale.US);
        for (String w : words)
            if (!hay.contains(w))
                return false;
        return true;
    }

    /** All statuses, for completeness checks in tests. */
    static List<Status> allStatuses() {
        return Arrays.asList(Status.values());
    }
}
