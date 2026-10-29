package com.atakmap.android.ghostmaps.plugin.core;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One installable file in the published repository: path, git blob SHA-1
 * (content identity) and size, plus values derived from them once.
 *
 * <h3>Product groups</h3>
 * Files are versions of the same product only when all of these match:
 * <ul>
 * <li>the <em>scope folder</em>: the file's folder with archive folders
 * removed, so {@code Iran/Kinetic Events/Archive/x.kmz} and
 * {@code Iran/Kinetic Events/y.kmz} share a scope, but files in different
 * incident folders never do;</li>
 * <li>the file kind (a map layer is never a version of an icon set);</li>
 * <li>whether the name carries a date (an undated "rolling" file such as
 * {@code Kinetic_Activities_Last_30_Days.kmz} is never superseded by a
 * dated snapshot, and vice versa);</li>
 * <li>the display name with date, version, case and punctuation removed.</li>
 * </ul>
 */
public final class RepoEntry {

    private static final Pattern ARCHIVE_SEGMENT = Pattern.compile(
            "^(?:\\d+[ _\\-]*)?archive(?:s|d)?\\b.*", Pattern.CASE_INSENSITIVE);

    private final String path;
    private final String sha;
    private final long size;
    private final String fileName;
    private final String folder;
    private final FileKind kind;
    private final FileName name;
    private final boolean archived;
    private final String scopeFolder;
    private final String groupKey;

    public RepoEntry(String path, String sha, long size) {
        this.path = path;
        this.sha = sha == null ? "" : sha.toLowerCase(Locale.US);
        this.size = size;
        int slash = path.lastIndexOf('/');
        this.fileName = slash < 0 ? path : path.substring(slash + 1);
        this.folder = slash < 0 ? "" : path.substring(0, slash);
        this.kind = FileKind.fromFileName(fileName);
        this.name = FileName.parse(fileName);

        StringBuilder scope = new StringBuilder();
        boolean arch = false;
        if (!folder.isEmpty()) {
            for (String seg : folder.split("/")) {
                if (isArchiveFolderName(seg)) {
                    arch = true;
                    continue;
                }
                if (scope.length() > 0)
                    scope.append('/');
                scope.append(seg);
            }
        }
        this.archived = arch;
        this.scopeFolder = scope.toString();
        this.groupKey = scopeFolder.toLowerCase(Locale.US) + '\n' + kind + '\n'
                + (name.hasDate() ? 'd' : 'u') + '\n' + normalize(name.getProduct());
    }

    /** True for folder names like "Archive", "0_Archive", "Archived Incidents", "2025 Archive". */
    public static boolean isArchiveFolderName(String segment) {
        String s = segment.trim();
        return ARCHIVE_SEGMENT.matcher(s).matches()
                || s.toLowerCase(Locale.US).matches(".*[ _\\-]archives?$");
    }

    /** Lower case, runs of non-alphanumerics folded to one space. */
    public static String normalize(String productName) {
        return productName.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ").trim();
    }

    public String path() {
        return path;
    }

    /** Git blob SHA-1, lower-case hex; identical contents ⇔ identical SHA. */
    public String sha() {
        return sha;
    }

    public long size() {
        return size;
    }

    public String fileName() {
        return fileName;
    }

    /** Folder part of the path, "" at the repository root. */
    public String folder() {
        return folder;
    }

    /** Kind by extension, or null when GhostMaps can't install this file. */
    public FileKind kind() {
        return kind;
    }

    public FileName name() {
        return name;
    }

    /** True if any folder on the path is an archive folder. */
    public boolean isArchived() {
        return archived;
    }

    /** Folder with archive folders removed; see the class comment. */
    public String scopeFolder() {
        return scopeFolder;
    }

    /** Files with equal group keys are versions of one product. */
    public String groupKey() {
        return groupKey;
    }

    /** Folder-independent product identity, for information only (never for updates). */
    public String looseProductKey() {
        return kind + "\n" + normalize(name.getProduct());
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof RepoEntry))
            return false;
        RepoEntry r = (RepoEntry) o;
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
