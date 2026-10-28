package com.atakmap.android.ghostmaps.plugin.core;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * A repo file GhostMaps downloaded and handed to ATAK's importer: which repo
 * version it was, where ATAK put it and how to remove it again.
 */
public final class InstalledFile {

    private final String repoPath;
    private final String sha;
    private final long size;
    private final String localPath;
    private final String contentType;
    private final String mimeType;
    private final long installedAt;

    public InstalledFile(String repoPath, String sha, long size,
            String localPath, String contentType, String mimeType,
            long installedAt) {
        this.repoPath = repoPath;
        this.sha = sha == null ? "" : sha;
        this.size = size;
        this.localPath = localPath == null ? "" : localPath;
        this.contentType = contentType == null ? "" : contentType;
        this.mimeType = mimeType == null ? "" : mimeType;
        this.installedAt = installedAt;
    }

    public String getRepoPath() {
        return repoPath;
    }

    public String getSha() {
        return sha;
    }

    public long getSize() {
        return size;
    }

    /** Where ATAK's importer placed the file (e.g. atak/overlays/x.kmz). */
    public String getLocalPath() {
        return localPath;
    }

    /** ATAK importer content type (e.g. "KML"), "" if unknown. */
    public String getContentType() {
        return contentType;
    }

    public String getMimeType() {
        return mimeType;
    }

    public long getInstalledAt() {
        return installedAt;
    }

    /** Same install, recorded under a new repo path (file moved in the repo). */
    public InstalledFile withRepoPath(String newRepoPath) {
        return new InstalledFile(newRepoPath, sha, size, localPath, contentType,
                mimeType, installedAt);
    }

    /** Same install, with its file now at {@code newLocalPath}. */
    public InstalledFile withLocalPath(String newLocalPath, String newContentType,
            String newMimeType, long when) {
        return new InstalledFile(repoPath, sha, size, newLocalPath, newContentType,
                newMimeType, when);
    }

    /** The installed version viewed as a repo entry (for name parsing). */
    public RepoFile asRepoFile() {
        return new RepoFile(repoPath, sha, size);
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("repoPath", repoPath);
        o.put("sha", sha);
        o.put("size", size);
        o.put("localPath", localPath);
        o.put("contentType", contentType);
        o.put("mimeType", mimeType);
        o.put("installedAt", installedAt);
        return o;
    }

    static InstalledFile fromJson(JSONObject o) {
        return new InstalledFile(o.optString("repoPath", ""),
                o.optString("sha", ""), o.optLong("size", -1),
                o.optString("localPath", ""), o.optString("contentType", ""),
                o.optString("mimeType", ""), o.optLong("installedAt", 0));
    }
}
