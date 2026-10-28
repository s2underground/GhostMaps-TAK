package com.atakmap.android.ghostmaps.plugin.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers which repo files GhostMaps installed, keyed by repo path, in a
 * small JSON file. Thread-safe; every change is written straight to disk
 * (via a temp file and rename, so a crash never leaves a half-written file).
 */
public final class InstalledStore {

    private final File file;
    private final Map<String, InstalledFile> entries = new LinkedHashMap<>();

    public InstalledStore(File file) {
        this.file = file;
        load();
    }

    public synchronized InstalledFile get(String repoPath) {
        return entries.get(repoPath);
    }

    public synchronized boolean isInstalled(String repoPath) {
        return entries.containsKey(repoPath);
    }

    public synchronized List<InstalledFile> all() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized void put(InstalledFile f) throws IOException {
        entries.put(f.getRepoPath(), f);
        save();
    }

    public synchronized void remove(String repoPath) throws IOException {
        if (entries.remove(repoPath) != null)
            save();
    }

    /**
     * Forgets entries whose local file is gone (e.g. the user deleted the
     * overlay in ATAK's Overlay Manager). Returns how many were dropped.
     */
    public synchronized int pruneMissing() throws IOException {
        int n = 0;
        for (String key : new ArrayList<>(entries.keySet())) {
            String p = entries.get(key).getLocalPath();
            if (p.isEmpty() || !new File(p).exists()) {
                entries.remove(key);
                n++;
            }
        }
        if (n > 0)
            save();
        return n;
    }

    private void load() {
        entries.clear();
        if (!file.isFile())
            return;
        try {
            JSONArray arr = new JSONObject(readAll(file)).optJSONArray("installed");
            if (arr == null)
                return;
            for (int i = 0; i < arr.length(); i++) {
                InstalledFile f = InstalledFile.fromJson(arr.getJSONObject(i));
                if (!f.getRepoPath().isEmpty())
                    entries.put(f.getRepoPath(), f);
            }
        } catch (IOException | JSONException e) {
            // unreadable store: start empty rather than failing the plugin
            entries.clear();
        }
    }

    private void save() throws IOException {
        try {
            JSONArray arr = new JSONArray();
            for (InstalledFile f : entries.values())
                arr.put(f.toJson());
            JSONObject root = new JSONObject();
            root.put("version", 1);
            root.put("installed", arr);
            writeAtomically(file, root.toString(2));
        } catch (JSONException e) {
            throw new IOException(e);
        }
    }

    static String readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static void writeAtomically(File f, String text) throws IOException {
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(f)) {
            // rename over an existing file can fail on some file systems
            if (!f.delete() || !tmp.renameTo(f))
                throw new IOException("Cannot write " + f);
        }
    }
}
