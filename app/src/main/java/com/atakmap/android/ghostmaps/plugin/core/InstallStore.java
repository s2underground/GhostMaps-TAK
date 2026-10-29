package com.atakmap.android.ghostmaps.plugin.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The list of files GhostMaps installed ({@code installed.json}).
 *
 * <p>Not thread-safe by design: the engine confines it to its single state
 * thread. Every change is written through {@link AtomicTextFile} before the
 * method returns. The store is a cache, not the only truth: files in
 * GhostMaps' folder can be re-identified by content hash, so a lost or
 * corrupt store never strands a file.</p>
 *
 * <p>A file that can't be read is moved aside (never overwritten) and the
 * backup is tried; {@link #recoveredFromCorruption()} tells the caller so it
 * can re-adopt files. Version-1 files from earlier builds are migrated:
 * records outside {@code ownDir} become {@link InstallRecord.Origin#LEGACY}.</p>
 */
public final class InstallStore {

    static final int VERSION = 2;

    private final AtomicTextFile file;
    private final File ownDir;
    private final Map<String, InstallRecord> records = new LinkedHashMap<>();
    private boolean recovered;

    public InstallStore(File storeFile, File ownDir, Clock clock) {
        this.file = new AtomicTextFile(storeFile);
        this.ownDir = ownDir;
        load(clock.now());
    }

    // ---------------------------------------------------------------- reads

    public List<InstallRecord> all() {
        return Collections.unmodifiableList(new ArrayList<>(records.values()));
    }

    public InstallRecord get(String id) {
        return records.get(id);
    }

    public int size() {
        return records.size();
    }

    public InstallRecord findByRepoPath(String repoPath) {
        for (InstallRecord r : records.values())
            if (r.repoPath().equals(repoPath))
                return r;
        return null;
    }

    /** True when the store file, or a record in it, was unreadable at load; files may need re-adopting. */
    public boolean recoveredFromCorruption() {
        return recovered;
    }

    // --------------------------------------------------------------- writes

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    /** Adds or replaces a record and saves. */
    public void put(InstallRecord r) throws IOException {
        InstallRecord prev = records.put(r.id(), r);
        try {
            save();
        } catch (IOException e) {
            if (prev == null)
                records.remove(r.id());
            else
                records.put(prev.id(), prev);
            throw e;
        }
    }

    /** Removes a record and saves; no-op if absent. */
    public void remove(String id) throws IOException {
        InstallRecord prev = records.remove(id);
        if (prev == null)
            return;
        try {
            save();
        } catch (IOException e) {
            records.put(id, prev);
            throw e;
        }
    }

    private void save() throws IOException {
        try {
            JSONArray arr = new JSONArray();
            for (InstallRecord r : records.values())
                arr.put(r.toJson());
            JSONObject root = new JSONObject();
            root.put("version", VERSION);
            root.put("installed", arr);
            file.write(root.toString(1));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    // ---------------------------------------------------------------- load

    private void load(long now) {
        records.clear();
        if (!file.exists())
            return;
        try {
            parseInto(file.read());
            return;
        } catch (IOException | JSONException e) {
            recovered = true;
            file.quarantine(now);
        }
        try {
            String bak = file.readBackup();
            if (bak != null) {
                parseInto(bak);
                saveQuietly(); // restore a readable main file from the backup
            }
        } catch (IOException | JSONException e) {
            records.clear();
        }
    }

    private void parseInto(String text) throws JSONException {
        records.clear();
        JSONObject root = new JSONObject(text);
        int version = root.optInt("version", 1);
        JSONArray arr = root.optJSONArray("installed");
        if (arr == null)
            return;
        boolean migrated = false;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            InstallRecord r = version >= VERSION ? InstallRecord.fromJson(o) : migrateV1(o);
            if (r != null)
                records.put(r.id(), r);
            else
                recovered = true; // a damaged record: its file is re-adopted if it can be identified
            migrated |= version < VERSION;
        }
        if (migrated)
            saveQuietly();
    }

    /** A save during load that fails is retried by the next change; never a reason to discard. */
    private void saveQuietly() {
        try {
            save();
        } catch (IOException ignored) {
            // keep the in-memory records
        }
    }

    /** Version 1: {repoPath, sha, size, localPath, contentType, mimeType, installedAt}. */
    private InstallRecord migrateV1(JSONObject o) {
        String repoPath = o.optString("repoPath", "");
        String local = o.optString("localPath", "");
        FileKind kind = FileKind.fromFileName(repoPath);
        if (repoPath.isEmpty() || local.isEmpty() || kind == null)
            return null;
        InstallRecord.Origin origin = isInside(new File(local), ownDir)
                ? InstallRecord.Origin.GHOSTMAPS : InstallRecord.Origin.LEGACY;
        long at = o.optLong("installedAt", 0);
        // state ACTIVE = "believed installed"; the reconciler checks ATAK
        return new InstallRecord(newId(), repoPath, o.optString("sha", ""),
                o.optLong("size", -1), kind, local, null, origin,
                InstallRecord.State.ACTIVE, at, at, null);
    }

    /** True if {@code f} is inside {@code dir} (canonical paths; no ".." tricks). */
    public static boolean isInside(File f, File dir) {
        if (dir == null)
            return false;
        try {
            String d = dir.getCanonicalPath() + File.separator;
            return f.getCanonicalPath().startsWith(d);
        } catch (IOException e) {
            return false;
        }
    }
}
