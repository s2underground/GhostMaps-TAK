package com.atakmap.android.ghostmaps.plugin.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads GitHub's {@code GET /repos/{o}/{r}/git/trees/{sha}?recursive=1}
 * response into a {@link Catalog}, keeping only file kinds GhostMaps can
 * install and skipping hidden paths.
 */
public final class TreeParser {

    /** Parsed listing plus GitHub's flag for listings cut short. */
    public static final class Result {
        public final Catalog catalog;
        public final boolean truncated;

        Result(Catalog catalog, boolean truncated) {
            this.catalog = catalog;
            this.truncated = truncated;
        }
    }

    private TreeParser() {
    }

    /**
     * @param commitSha the commit the tree belongs to; downloads are pinned to it
     */
    public static Result parse(String json, String commitSha) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray tree = root.getJSONArray("tree");
        List<RepoEntry> out = new ArrayList<>(tree.length());
        for (int i = 0; i < tree.length(); i++) {
            JSONObject e = tree.getJSONObject(i);
            if (!"blob".equals(e.optString("type")))
                continue;
            String path = e.optString("path", "");
            String sha = e.optString("sha", "");
            long size = e.optLong("size", -1);
            if (path.isEmpty() || sha.length() != 40 || size < 0 || isHidden(path))
                continue;
            RepoEntry entry = new RepoEntry(path, sha, size);
            if (entry.kind() != null)
                out.add(entry);
        }
        return new Result(new Catalog(commitSha, out), root.optBoolean("truncated", false));
    }

    private static boolean isHidden(String path) {
        for (String seg : path.split("/"))
            if (seg.startsWith("."))
                return true;
        return false;
    }
}
