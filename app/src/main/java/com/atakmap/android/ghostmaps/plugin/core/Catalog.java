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
 * versions of one map grouped, newest first) and by folder for browsing.
 * Immutable once built.
 */
public final class Catalog {

    /** Newest first; undated files after dated ones, then by name. */
    public static final Comparator<RepoFile> NEWEST_FIRST = new Comparator<RepoFile>() {
        @Override
        public int compare(RepoFile a, RepoFile b) {
            int c = b.parsed().compareAge(a.parsed());
            if (c != 0)
                return c;
            return a.getPath().compareToIgnoreCase(b.getPath());
        }
    };

    private final List<RepoFile> files;
    private final Map<String, RepoFile> byPath = new HashMap<>();
    private final Map<String, List<RepoFile>> byProduct = new LinkedHashMap<>();

    public Catalog(List<RepoFile> files) {
        this.files = Collections.unmodifiableList(new ArrayList<>(files));
        for (RepoFile f : this.files) {
            byPath.put(f.getPath(), f);
            List<RepoFile> versions = byProduct.get(f.productKey());
            if (versions == null) {
                versions = new ArrayList<>();
                byProduct.put(f.productKey(), versions);
            }
            versions.add(f);
        }
        for (List<RepoFile> versions : byProduct.values())
            Collections.sort(versions, NEWEST_FIRST);
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
     * Newest version of each product outside archive folders, newest first,
     * optionally filtered: every word of {@code filter} must appear in the
     * file's path (case-insensitive).
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
