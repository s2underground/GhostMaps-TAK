package com.atakmap.android.ghostmaps.plugin.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One file GhostMaps put on the device, and where it is in its lifecycle.
 * Immutable; changes produce a new record via the {@code with…} methods.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *  (download verified)
 *        │
 *     PLACED ──────────► IMPORT_REQUESTED ──────► ACTIVE
 *  file is in              ATAK was asked          ATAK has it loaded
 *  atak/GhostMaps          to load it              (or the icon set exists)
 *                                                     │
 *                                REMOVING ◄───────────┘ (user removes / replaced)
 * </pre>
 * Every state is written to disk <em>before</em> the side effect it
 * announces, so a crash at any point leaves a record that the reconciler
 * can finish or undo.
 *
 * <h3>Ownership</h3>
 * {@link Origin#GHOSTMAPS} records are files inside GhostMaps' own folder;
 * only those are ever removed. {@link Origin#LEGACY} records come from early
 * test builds that put files elsewhere (e.g. atak/overlays); GhostMaps never
 * changes or deletes those files, it can only forget the record.
 */
public final class InstallRecord {

    public enum Origin {
        GHOSTMAPS, LEGACY
    }

    public enum State {
        PLACED, IMPORT_REQUESTED, ACTIVE, REMOVING
    }

    private final String id;
    private final String repoPath;
    private final String sha;
    private final long size;
    private final FileKind kind;
    private final String localPath;
    private final String iconSetUid;
    private final Origin origin;
    private final State state;
    private final long stateSince;
    private final long installedAt;
    private final List<String> replaces;

    public InstallRecord(String id, String repoPath, String sha, long size, FileKind kind,
            String localPath, String iconSetUid, Origin origin, State state,
            long stateSince, long installedAt, List<String> replaces) {
        if (id == null || id.isEmpty() || repoPath == null || kind == null
                || localPath == null || origin == null || state == null)
            throw new IllegalArgumentException("incomplete install record");
        this.id = id;
        this.repoPath = repoPath;
        this.sha = sha == null ? "" : sha;
        this.size = size;
        this.kind = kind;
        this.localPath = localPath;
        this.iconSetUid = iconSetUid == null || iconSetUid.isEmpty() ? null : iconSetUid;
        this.origin = origin;
        this.state = state;
        this.stateSince = stateSince;
        this.installedAt = installedAt;
        this.replaces = replaces == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(replaces));
    }

    public String id() {
        return id;
    }

    /** Path in the repository when installed (updated when the file moves). */
    public String repoPath() {
        return repoPath;
    }

    /** Git blob SHA-1 of the installed bytes. */
    public String sha() {
        return sha;
    }

    public long size() {
        return size;
    }

    public FileKind kind() {
        return kind;
    }

    /** Absolute path of the file on the device. */
    public String localPath() {
        return localPath;
    }

    /** ATAK icon-set UID for {@link FileKind#ICON_SET} records once known, else null. */
    public String iconSetUid() {
        return iconSetUid;
    }

    public Origin origin() {
        return origin;
    }

    public boolean isManaged() {
        return origin == Origin.GHOSTMAPS;
    }

    public State state() {
        return state;
    }

    /** When {@link #state()} was entered (ms since epoch). */
    public long stateSince() {
        return stateSince;
    }

    public long installedAt() {
        return installedAt;
    }

    /** IDs of records to remove once this one is ACTIVE (an update in progress). */
    public List<String> replaces() {
        return replaces;
    }

    /** The installed version viewed as a repository entry. */
    public RepoEntry asEntry() {
        return new RepoEntry(repoPath, sha, size);
    }

    public InstallRecord withState(State s, long when) {
        return new InstallRecord(id, repoPath, sha, size, kind, localPath, iconSetUid, origin,
                s, when, installedAt, replaces);
    }

    public InstallRecord withRepoPath(String p) {
        return new InstallRecord(id, p, sha, size, kind, localPath, iconSetUid, origin, state,
                stateSince, installedAt, replaces);
    }

    public InstallRecord withIconSetUid(String uid) {
        return new InstallRecord(id, repoPath, sha, size, kind, localPath, uid, origin, state,
                stateSince, installedAt, replaces);
    }

    public InstallRecord withReplaces(List<String> ids) {
        return new InstallRecord(id, repoPath, sha, size, kind, localPath, iconSetUid, origin,
                state, stateSince, installedAt, ids);
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("repoPath", repoPath);
        o.put("sha", sha);
        o.put("size", size);
        o.put("kind", kind.name());
        o.put("localPath", localPath);
        if (iconSetUid != null)
            o.put("iconSetUid", iconSetUid);
        o.put("origin", origin.name());
        o.put("state", state.name());
        o.put("stateSince", stateSince);
        o.put("installedAt", installedAt);
        JSONArray r = new JSONArray();
        for (String s : replaces)
            r.put(s);
        o.put("replaces", r);
        return o;
    }

    /** Reads a v2 record; returns null for a record that can't be trusted. */
    static InstallRecord fromJson(JSONObject o) {
        try {
            List<String> rep = new ArrayList<>();
            JSONArray r = o.optJSONArray("replaces");
            if (r != null)
                for (int i = 0; i < r.length(); i++)
                    rep.add(r.getString(i));
            return new InstallRecord(o.getString("id"), o.getString("repoPath"),
                    o.optString("sha", ""), o.optLong("size", -1),
                    FileKind.valueOf(o.getString("kind")), o.getString("localPath"),
                    o.optString("iconSetUid", null), Origin.valueOf(o.getString("origin")),
                    State.valueOf(o.getString("state")), o.optLong("stateSince", 0),
                    o.optLong("installedAt", 0), rep);
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return id + " " + state + " " + repoPath + " -> " + localPath;
    }
}
