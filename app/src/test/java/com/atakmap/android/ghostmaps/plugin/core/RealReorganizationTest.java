package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.List;

/**
 * The GhostMaps repo as it was on the morning of Oct 28, 2026 and after the
 * afternoon "October 28 Update", which archived ten files (Spokane fire set,
 * Reno wildfires, Coeur d'Alene, Garden Grove, two China IPB versions) and
 * refreshed the rolling 30-day pull.
 */
public class RealReorganizationTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static Catalog load(String name) throws Exception {
        return new Catalog(TreeParser.parse(CatalogTest.readResource(name)).files);
    }

    @Test
    public void morningInstallsSurviveTheAfternoonReorganization() throws Exception {
        Catalog before = load("/ghostmaps-tree-2026-10-28.json");
        Catalog after = load("/ghostmaps-tree-2026-10-28-afternoon.json");
        InstalledStore store = new InstalledStore(new File(tmp.getRoot(), "installed.json"));
        String local = tmp.newFile("x.kmz").getPath();

        // someone who installed every product in Latest this morning
        for (RepoFile f : before.latest(null))
            store.put(new InstalledFile(f.getPath(), f.getSha(), f.getSize(), local, "KML", "", 1L));
        assertEquals(31, store.size());

        // the eight archived installs are re-pointed, nothing downloaded
        assertEquals(8, UpdateCheck.relinkMoved(store, after));
        assertTrue(store.isInstalled("Incident Response Products for ATAK (KMZs and Documents)/0_Archive/"
                + "Coeur d'Alene, Idaho Ambush - June 29, 2025/Coeur_dAlene_Idaho_Ambush_June_29_2025_v1.kmz"));

        List<UpdateCheck.Status> statuses = UpdateCheck.checkAll(store, after);
        int upToDate = 0;
        for (UpdateCheck.Status s : statuses) {
            if (s.kind == UpdateCheck.Kind.UP_TO_DATE) {
                upToDate++;
            } else {
                // only the rolling 30-day pull changed
                assertEquals(UpdateCheck.Kind.CHANGED, s.kind);
                assertEquals("Kinetic_Activities_Last_30_Days.kmz", s.target.fileName());
            }
        }
        assertEquals(30, upToDate);
        assertEquals(1, UpdateCheck.countUpdates(statuses));

        // archived products leave Latest but stay browsable
        assertEquals(23, after.latest(null).size());
        assertEquals(165, after.size());
    }
}
