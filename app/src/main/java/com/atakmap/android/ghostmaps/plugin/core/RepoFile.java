package com.atakmap.android.ghostmaps.plugin.core;

import java.util.Locale;

/**
 * One downloadable file in the GhostMaps repository, as listed by GitHub's
 * git tree API: its path in the repo, git blob SHA-1 and size in bytes.
 * The name-derived fields (product, date, version) are parsed once here.
 */
public final class RepoFile {

    private final String path;
    private final String sha;
    private final long size;
    private final FileName parsed;

    public RepoFile(String path, String sha, long size) {
        this.path = path;
        this.sha = sha == null ? "" : sha.toLowerCase(Locale.US);
        this.size = size;
        this.parsed = FileName.parse(fileName());
    }

    /** Path inside the repository, '/' separated, e.g. "A/B/file.kmz". */
    public String getPath() {
        return path;
    }

    /** Git blob SHA-1 (lower-case hex); changes whenever the file's bytes change. */
    public String getSha() {
        return sha;
    }

    public long getSize() {
        return size;
    }

    /** Last path segment, e.g. "file.kmz". */
    public String fileName() {
        int i = path.lastIndexOf('/');
        return i < 0 ? path : path.substring(i + 1);
    }

    /** Folder part of the path ("" for files at the repo root). */
    public String folder() {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i);
    }

    /** Lower-case extension without the dot, or "". */
    public String extension() {
        String n = fileName();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.US);
    }

    public FileName parsed() {
        return parsed;
    }

    /**
     * Identifies the product this file is one version of: the file name with
     * its date and version removed, ignoring case, punctuation and folder.
     * "Iran_Kinetic_Events_October_10_2026_v2.kmz" and
     * "Iran Kinetic Events_July_8_2026.kmz" share a key wherever they live,
     * so reorganizing folders never splits a product or orphans an install.
     */
    public String productKey() {
        return productKey(parsed.getProduct());
    }

    /** Normalized product name: lower case, runs of non-alphanumerics → one space. */
    public static String productKey(String productName) {
        return productName.toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", " ").trim();
    }

    /** True for anything under a folder named like "0_Archive" or "Archive". */
    public boolean isArchived() {
        for (String seg : folder().split("/")) {
            String s = seg.trim().toLowerCase(Locale.US);
            if (s.equals("archive") || s.matches("\\d*_?archive.*"))
                return true;
        }
        return false;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RepoFile))
            return false;
        RepoFile r = (RepoFile) o;
        return path.equals(r.path) && sha.equals(r.sha);
    }

    @Override
    public int hashCode() {
        return path.hashCode() * 31 + sha.hashCode();
    }

    @Override
    public String toString() {
        return path;
    }
}
