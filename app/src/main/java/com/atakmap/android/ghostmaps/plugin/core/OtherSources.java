package com.atakmap.android.ghostmaps.plugin.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Map layers ATAK has loaded from outside GhostMaps' folder (DataSync, the
 * Import Manager, data packages…), indexed by display name, so the panel can
 * warn before adding a second copy. Information only: GhostMaps never
 * changes or removes these files.
 */
public final class OtherSources {

    public static final OtherSources NONE = new OtherSources(Collections.<String>emptyList());

    private final Map<String, List<String>> byProduct = new HashMap<>();

    /** @param absolutePaths files loaded by other sources */
    public OtherSources(Collection<String> absolutePaths) {
        for (String p : absolutePaths) {
            RepoEntry e = new RepoEntry(p.substring(p.lastIndexOf('/') + 1), "", 0);
            if (e.kind() != FileKind.MAP_LAYER)
                continue;
            List<String> l = byProduct.get(e.looseProductKey());
            if (l == null) {
                l = new ArrayList<>();
                byProduct.put(e.looseProductKey(), l);
            }
            l.add(p);
        }
    }

    /** Files from other sources that look like a version of {@code e}. */
    public List<String> matching(RepoEntry e) {
        List<String> l = byProduct.get(e.looseProductKey());
        return l == null ? Collections.<String>emptyList() : Collections.unmodifiableList(l);
    }
}
