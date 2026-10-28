package com.atakmap.android.ghostmaps.plugin.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reads the response of GitHub's
 * {@code GET /repos/{owner}/{repo}/git/trees/{branch}?recursive=1}
 * into the list of files ATAK can import.
 */
public final class TreeParser {

    /** File types ATAK's Import Manager handles that the repo may hold. */
    public static final Set<String> IMPORTABLE = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "kmz", "kml", "zip", "gpx", "geojson", "gpkg", "shpz",
                    "drw", "lpt", "tif", "tiff", "sqlite")));

    /** Parsed listing plus GitHub's flag for listings cut short. */
    public static final class Result {
        public final List<RepoFile> files;
        public final boolean truncated;

        Result(List<RepoFile> files, boolean truncated) {
            this.files = files;
            this.truncated = truncated;
        }
    }

    private TreeParser() {
    }

    public static Result parse(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        JSONArray tree = root.getJSONArray("tree");
        List<RepoFile> out = new ArrayList<>(tree.length());
        for (int i = 0; i < tree.length(); i++) {
            JSONObject e = tree.getJSONObject(i);
            if (!"blob".equals(e.optString("type")))
                continue;
            String path = e.optString("path", "");
            RepoFile f = new RepoFile(path, e.optString("sha", ""),
                    e.optLong("size", -1));
            if (isHidden(path) || !IMPORTABLE.contains(f.extension()))
                continue;
            out.add(f);
        }
        return new Result(out, root.optBoolean("truncated", false));
    }

    private static boolean isHidden(String path) {
        for (String seg : path.split("/"))
            if (seg.startsWith("."))
                return true;
        return false;
    }
}
