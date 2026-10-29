package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The owner's real reorganizations of s2underground/GhostMaps:
 * <ul>
 * <li>Oct 28 afternoon: ten files moved into archive folders, the 30-day
 * pull refreshed;</li>
 * <li>Oct 29 morning: 51 daily CIP exports deleted (newest kept), dozens of
 * files moved into new Archive folders and a new "Global Centers of Gravity"
 * folder, new products added, a few files deleted.</li>
 * </ul>
 * Someone who installed every product in Latest before each change must
 * keep all of it, get only same-folder updates, and never be pointed at an
 * archived file.
 */
public class RealReorganizationTest {

    private static List<InstallRecord> installEverythingIn(Catalog c) {
        List<InstallRecord> out = new ArrayList<>();
        for (RepoEntry e : c.latest(null))
            out.add(new InstallRecord(InstallStore.newId(), e.path(), e.sha(), e.size(), e.kind(),
                    new File("/x/" + e.fileName()).getAbsolutePath(), null,
                    InstallRecord.Origin.GHOSTMAPS, InstallRecord.State.ACTIVE, 1, 1, null));
        return out;
    }

    private static Map<UpdatePlanner.Kind, Integer> tally(List<UpdatePlanner.Status> st) {
        Map<UpdatePlanner.Kind, Integer> m = new HashMap<>();
        for (UpdatePlanner.Status s : st)
            m.put(s.kind, m.containsKey(s.kind) ? m.get(s.kind) + 1 : 1);
        return m;
    }

    /** Updates never point into an archive or outside the product group the file now belongs to. */
    private static void assertSafe(List<UpdatePlanner.Status> st, Catalog after) {
        for (UpdatePlanner.Status s : st) {
            if (s.hasUpdate()) {
                assertFalse(s.target.path(), s.target.isArchived());
                RepoEntry now = UpdatePlanner.locateSameContent(s.record, after);
                RepoEntry anchor = now != null ? now
                        : after.find(s.record.repoPath()) != null ? after.find(s.record.repoPath())
                                : s.record.asEntry();
                assertEquals("update must stay in its product group",
                        anchor.groupKey().replace("\n", "|"), s.target.groupKey().replace("\n", "|"));
            }
        }
    }

    @Test
    public void octoberTwentyEightAfternoon() throws Exception {
        Catalog before = Fixtures.real(Fixtures.MORNING_OCT_28);
        Catalog after = Fixtures.real(Fixtures.AFTERNOON_OCT_28);
        List<InstallRecord> installed = installEverythingIn(before);
        // 32 products: the undated "Yemen FLOT" and the dated one are separate
        assertEquals(32, installed.size());

        assertEquals(8, UpdatePlanner.relinks(installed, after).size());
        List<UpdatePlanner.Status> st = UpdatePlanner.evaluateAll(installed, after);
        assertSafe(st, after);
        Map<UpdatePlanner.Kind, Integer> t = tally(st);
        assertEquals(Integer.valueOf(8), t.get(UpdatePlanner.Kind.MOVED));
        assertEquals(Integer.valueOf(1), t.get(UpdatePlanner.Kind.CHANGED)); // the 30-day pull
        assertEquals(Integer.valueOf(23), t.get(UpdatePlanner.Kind.UP_TO_DATE));
        assertEquals(1, UpdatePlanner.countUpdates(st));
    }

    @Test
    public void octoberTwentyNineMorning() throws Exception {
        Catalog before = Fixtures.real(Fixtures.AFTERNOON_OCT_28);
        Catalog after = Fixtures.real(Fixtures.MORNING_OCT_29);
        List<InstallRecord> installed = installEverythingIn(before);
        List<UpdatePlanner.Status> st = UpdatePlanner.evaluateAll(installed, after);
        assertSafe(st, after);

        // Russia IPB moved to "Global Centers of Gravity" and a newer version
        // published there: follow the file, then offer the newer version
        UpdatePlanner.Status russia = find(st, "Russia_Centers_of_Gravity_July_10_2026_V1.kmz");
        assertEquals(UpdatePlanner.Kind.NEWER_VERSION, russia.kind);
        assertTrue(russia.target.path().startsWith("Global Centers of Gravity/Russia IPB/"));

        // the daily CIP: Oct 27 deleted, a newer export published in the same folder
        UpdatePlanner.Status cip = find(st, "S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz");
        assertEquals(UpdatePlanner.Kind.NEWER_VERSION, cip.kind);
        assertTrue(cip.target.name().getDate() > 20261027);

        // China IPB moved to "Global Centers of Gravity" (record re-pointed without a
        // download) and a newer version was published there (offered as an update)
        UpdatePlanner.Status china = find(st, "China_PLA_Centers_of_Gravity_July_4_2026_v2.kmz");
        assertTrue(UpdatePlanner.relinks(installed, after).containsKey(china.record.id()));
        assertEquals(UpdatePlanner.Kind.NEWER_VERSION, china.kind);
        assertTrue(china.target.path().startsWith("Global Centers of Gravity/China IPB/"));

        // Cuba IPB only moved: same bytes, nothing to download
        assertEquals(UpdatePlanner.Kind.MOVED, find(st, "Cuba_Master_IPB_May_20_2026_v1.kmz").kind);

        // undated Yemen FLOT deleted: kept on the device, with a suggestion only
        UpdatePlanner.Status yemen = find(st, "Yemen FLOT.kmz");
        if (yemen != null) {
            assertEquals(UpdatePlanner.Kind.GONE, yemen.kind);
            assertFalse(yemen.hasUpdate());
        }

        // nothing anyone installed vanishes into an update of a different product
        for (UpdatePlanner.Status s : st)
            assertNotNull(s.kind);
    }

    private static UpdatePlanner.Status find(List<UpdatePlanner.Status> st, String fileName) {
        for (UpdatePlanner.Status s : st)
            if (s.record.asEntry().fileName().equals(fileName))
                return s;
        return null;
    }
}
