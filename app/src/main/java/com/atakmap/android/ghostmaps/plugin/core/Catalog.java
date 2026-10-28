package com.atakmap.android.ghostmaps.plugin.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The repository listing, organized two ways: by product (all dated
 * versions of one map grouped, newest first, regardless of which folders
 * they're in) and by folder for browsing. Immutable once built.
 */
public final class Catalog {

    /**
     * Newest first; undated files after dated ones; for equal age, files
     * outside archive folders first, then by path.
     */
    public static final Comparator<RepoFile> NEWEST_FIRST = new Comparator<RepoFile>() {
        @Override
        public int compare(RepoFile a, RepoFile b) {
            int c = b.parsed().compareAge(a.parsed());
            if (c != 0)
                return c;
            if (a.isArchived() != b.isArchived())
                return a.isArchived() ? 1 : -1;
            return a.getPath().compareToIgnoreCase(b.getPath());
        }
    };

    private final List<RepoFile> files;
    private final Map<String, RepoFile> byPath = new HashMap<>();
    private final Map<String, List<RepoFile>> byProduct = new LinkedHashMap<>();
    private final Map<String, List<RepoFile>> bySha = new HashMap<>();
    private final Map<String, List<RepoFile>> byName = new HashMap<>();

    public Catalog(List<RepoFile> files) {
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
        for (RepoFile f : this.files) {
            byPath.put(f.getPath(), f);
            add(byProduct, f.productKey(), f);
            add(bySha, f.getSha(), f);
            add(byName, f.fileName().toLowerCase(Locale.US), f);
        }
        for (List<RepoFile> versions : byProduct.values())
            Collections.sort(versions, NEWEST_FIRST);
        for (List<RepoFile> same : bySha.values())
            Collections.sort(same, NEWEST_FIRST);
        for (List<RepoFile> same : byName.values())
            Collections.sort(same, NEWEST_FIRST);
    }

    private static void add(Map<String, List<RepoFile>> map, String key, RepoFile f) {
        List<RepoFile> l = map.get(key);
        if (l == null) {
            l = new ArrayList<>();
            map.put(key, l);
        }
        l.add(f);
    }

    public List<RepoFile> getFiles() {
        return files;
    }

    public int size() {
        return files.size();
    }

    public RepoFile find(String path) {
        return byPath.get(path);
    }

    /** Files with exactly these contents (same git blob SHA), anywhere in the repo. */
    public List<RepoFile> findBySha(String sha) {
        List<RepoFile> l = bySha.get(sha == null ? "" : sha.toLowerCase(Locale.US));
        return l == null ? Collections.<RepoFile>emptyList() : l;
    }

    /** Files with this file name (case-insensitive), anywhere in the repo. */
    public List<RepoFile> findByName(String fileName) {
        List<RepoFile> l = byName.get(fileName.toLowerCase(Locale.US));
        return l == null ? Collections.<RepoFile>emptyList() : l;
    }

    /** All versions of a product (see {@link RepoFile#productKey()}), newest first. */
    public List<RepoFile> versionsOf(String productKey) {
        List<RepoFile> v = byProduct.get(productKey);
        return v == null ? Collections.<RepoFile>emptyList() : v;
    }

    /** All versions of the product {@code f} belongs to, newest first. */
    public List<RepoFile> versionsOf(RepoFile f) {
        List<RepoFile> v = byProduct.get(f.productKey());
        return v == null ? Collections.singletonList(f) : v;
    }

    /** Newest version of the product {@code f} belongs to. */
    public RepoFile latestOf(RepoFile f) {
        return versionsOf(f).get(0);
    }

    /**
     * Newest version of each product whose newest version is outside the
     * archive, newest first, optionally filtered: every word of
     * {@code filter} must appear in the file's path (case-insensitive).
     */
    public List<RepoFile> latest(String filter) {
        String[] words = words(filter);
        List<RepoFile> out = new ArrayList<>();
        for (List<RepoFile> versions : byProduct.values()) {
            RepoFile newest = versions.get(0);
            if (!newest.isArchived() && matches(newest, words))
                out.add(newest);
        }
        Collections.sort(out, NEWEST_FIRST);
        return out;
    }

    /** All files matching {@code filter} (see {@link #latest}), newest first. */
    public List<RepoFile> search(String filter) {
        String[] words = words(filter);
        List<RepoFile> out = new ArrayList<>();
        for (RepoFile f : files)
            if (matches(f, words))
                out.add(f);
        Collections.sort(out, NEWEST_FIRST);
        return out;
    }

    /**
     * Direct sub-folders of {@code folder} ("" for the repo root) with the
     * number of files under each, sorted by name.
     */
    public Map<String, Integer> subfolders(String folder) {
        String prefix = folder.isEmpty() ? "" : folder + "/";
        Map<String, Integer> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (RepoFile f : files) {
            String p = f.getPath();
            if (!p.startsWith(prefix))
                continue;
            String rest = p.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0)
                continue;
            String child = prefix + rest.substring(0, slash);
            Integer n = out.get(child);
            out.put(child, n == null ? 1 : n + 1);
        }
        return out;
    }

    /** True if any file lives in or under {@code folder}. */
    public boolean hasFolder(String folder) {
        if (folder.isEmpty())
            return true;
        String prefix = folder + "/";
        for (RepoFile f : files)
            if (f.getPath().startsWith(prefix))
                return true;
        return false;
    }

    /** Files directly inside {@code folder}, newest first. */
    public List<RepoFile> filesIn(String folder) {
        List<RepoFile> out = new ArrayList<>();
        for (RepoFile f : files)
            if (f.folder().equals(folder))
                out.add(f);
        Collections.sort(out, NEWEST_FIRST);
        return out;
    }

    private static String[] words(String filter) {
        if (filter == null || filter.trim().isEmpty())
            return new String[0];
        return filter.trim().toLowerCase(Locale.US).split("\\s+");
    }

    private static boolean matches(RepoFile f, String[] words) {
        if (words.length == 0)
            return true;
        String hay = f.getPath().replace('_', ' ').toLowerCase(Locale.US);
        for (String w : words)
            if (!hay.contains(w))
                return false;
        return true;
    }
}
