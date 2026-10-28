package com.atakmap.android.ghostmaps.plugin.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Files ATAK has loaded that GhostMaps did not install (DataSync, the Import
 * Manager, a data package…), indexed by product so the panel can say "this
 * map is already on the device from another source". GhostMaps only reads
 * this; it never changes or removes those files.
 */
public final class OtherSources {

    public static final OtherSources NONE = new OtherSources(Collections.<String>emptyList());

    private final Map<String, List<String>> byProduct = new HashMap<>();

    /** @param localPaths absolute paths of files imported by other means */
    public OtherSources(Collection<String> localPaths) {
        for (String p : localPaths) {
            String name = p.substring(p.lastIndexOf('/') + 1);
            String key = RepoFile.productKey(FileName.parse(name).getProduct());
            List<String> l = byProduct.get(key);
            if (l == null) {
                l = new ArrayList<>();
                byProduct.put(key, l);
            }
            l.add(p);
        }
    }

    /** Paths of other-source files that are a version of {@code f}'s product. */
    public List<String> forProduct(RepoFile f) {
        List<String> l = byProduct.get(f.productKey());
        return l == null ? Collections.<String>emptyList() : l;
    }

    public boolean isEmpty() {
        return byProduct.isEmpty();
    }
}
