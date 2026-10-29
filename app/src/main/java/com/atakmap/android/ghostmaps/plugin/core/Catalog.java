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
 * Immutable view of one published repository state (one commit): every
 * installable file, indexed by path, content SHA, file name and product
 * group. Safe to share between threads.
 */
public final class Catalog {

    /** Newest first; on equal age, files outside archives first, then by path. */
    public static final Comparator<RepoEntry> NEWEST_FIRST = new Comparator<RepoEntry>() {
        @Override
        public int compare(RepoEntry a, RepoEntry b) {
            int c = b.name().compareAge(a.name());
            if (c != 0)
                return c;
            if (a.isArchived() != b.isArchived())
                return a.isArchived() ? 1 : -1;
            return a.path().compareToIgnoreCase(b.path());
        }
    };

    private final String commitSha;
    private final List<RepoEntry> entries;
    private final Map<String, RepoEntry> byPath = new HashMap<>();
    private final Map<String, List<RepoEntry>> bySha = new HashMap<>();
    private final Map<String, List<RepoEntry>> byName = new HashMap<>();
    private final Map<String, List<RepoEntry>> byGroup = new LinkedHashMap<>();
    private final Map<String, List<RepoEntry>> byLooseProduct = new HashMap<>();

    public Catalog(String commitSha, List<RepoEntry> entries) {
        this.commitSha = commitSha == null ? "" : commitSha;
        List<RepoEntry> sorted = new ArrayList<>(entries);
        Collections.sort(sorted, NEWEST_FIRST);
        this.entries = Collections.unmodifiableList(sorted);
        for (RepoEntry e : this.entries) {
            byPath.put(e.path(), e);
            add(bySha, e.sha(), e);
            add(byName, e.fileName().toLowerCase(Locale.US), e);
            add(byGroup, e.groupKey(), e);
            add(byLooseProduct, e.looseProductKey(), e);
        }
        // lists inherit NEWEST_FIRST order from the sorted entries
    }

    private static void add(Map<String, List<RepoEntry>> m, String k, RepoEntry e) {
        List<RepoEntry> l = m.get(k);
        if (l == null) {
            l = new ArrayList<>(2);
            m.put(k, l);
        }
        l.add(e);
    }

    private static List<RepoEntry> get(Map<String, List<RepoEntry>> m, String k) {
        List<RepoEntry> l = m.get(k);
        return l == null ? Collections.<RepoEntry>emptyList() : Collections.unmodifiableList(l);
    }

    /** Commit the listing describes; downloads are pinned to it. */
    public String commitSha() {
        return commitSha;
    }

    /** All entries, newest first. */
    public List<RepoEntry> entries() {
        return entries;
    }

    public int size() {
        return entries.size();
    }

    public RepoEntry find(String path) {
        return byPath.get(path);
    }

    /** Entries with exactly these contents, newest first. */
    public List<RepoEntry> findBySha(String sha) {
        return get(bySha, sha == null ? "" : sha.toLowerCase(Locale.US));
    }

    /** Entries with this file name (case-insensitive), newest first. */
    public List<RepoEntry> findByFileName(String fileName) {
        return get(byName, fileName.toLowerCase(Locale.US));
    }

    /** All versions in a product group, newest first, archived ones included. */
    public List<RepoEntry> group(String groupKey) {
        return get(byGroup, groupKey);
    }

    /** All versions of {@code e}'s product, newest first. */
    public List<RepoEntry> versionsOf(RepoEntry e) {
        List<RepoEntry> l = group(e.groupKey());
        return l.isEmpty() ? Collections.singletonList(e) : l;
    }

    /** Newest version outside archive folders in a group, or null if all are archived. */
    public RepoEntry newestLive(String groupKey) {
        for (RepoEntry e : group(groupKey))
            if (!e.isArchived())
                return e;
        return null;
    }

    /** Same display name and kind in any folder; for suggestions only. */
    public List<RepoEntry> looselyRelated(String looseProductKey) {
        return get(byLooseProduct, looseProductKey);
    }

    /**
     * The newest live version of every product that has one, newest first.
     * Every word of {@code filter} must appear in the path.
     */
    public List<RepoEntry> latest(String filter) {
        String[] words = words(filter);
        List<RepoEntry> out = new ArrayList<>();
        for (String key : byGroup.keySet()) {
            RepoEntry newest = newestLive(key);
            if (newest != null && matches(newest, words))
                out.add(newest);
        }
        Collections.sort(out, NEWEST_FIRST);
        return out;
    }

    /** All entries matching {@code filter}, newest first. */
    public List<RepoEntry> search(String filter) {
        String[] words = words(filter);
        List<RepoEntry> out = new ArrayList<>();
        for (RepoEntry e : entries)
            if (matches(e, words))
                out.add(e);
        return out;
    }

    /** Direct sub-folders of {@code folder} ("" = root) with file counts under each. */
    public Map<String, Integer> subfolders(String folder) {
        String prefix = folder.isEmpty() ? "" : folder + "/";
        Map<String, Integer> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (RepoEntry e : entries) {
            String p = e.path();
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

    /** True if any entry lives in or under {@code folder}. */
    public boolean hasFolder(String folder) {
        if (folder.isEmpty())
            return true;
        String prefix = folder + "/";
        for (RepoEntry e : entries)
            if (e.path().startsWith(prefix))
                return true;
        return false;
    }

    /** Entries directly inside {@code folder}, newest first. */
    public List<RepoEntry> filesIn(String folder) {
        List<RepoEntry> out = new ArrayList<>();
        for (RepoEntry e : entries)
            if (e.folder().equals(folder))
                out.add(e);
        return out;
    }

    static String[] words(String filter) {
        if (filter == null || filter.trim().isEmpty())
            return new String[0];
        return filter.trim().toLowerCase(Locale.US).split("\\s+");
    }

    static boolean matches(RepoEntry e, String[] words) {
        if (words.length == 0)
            return true;
        String hay = e.path().replace('_', ' ').toLowerCase(Locale.US);
        for (String w : words)
            if (!hay.contains(w))
                return false;
        return true;
    }
}
