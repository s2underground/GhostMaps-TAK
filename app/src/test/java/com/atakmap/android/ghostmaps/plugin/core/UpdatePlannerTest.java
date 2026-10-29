package com.atakmap.android.ghostmaps.plugin.core;

import static com.atakmap.android.ghostmaps.plugin.core.Fixtures.cat;
import static com.atakmap.android.ghostmaps.plugin.core.Fixtures.e;
import static com.atakmap.android.ghostmaps.plugin.core.Fixtures.legacy;
import static com.atakmap.android.ghostmaps.plugin.core.Fixtures.managed;
import static com.atakmap.android.ghostmaps.plugin.core.Fixtures.sha;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Every repository change the owner might make, and what an installed copy
 * should do about it. Names starting with P refer to the review's probes.
 */
public class UpdatePlannerTest {

    private static final File LOCAL = new File("/tmp/ghostmaps-test/x.kmz");

    private static UpdatePlanner.Status eval(InstallRecord r, Catalog c, InstallRecord... others) {
        java.util.Set<String> shas = new HashSet<>();
        shas.add(r.sha());
        for (InstallRecord o : others)
            shas.add(o.sha());
        return UpdatePlanner.evaluate(r, c, shas);
    }

    @Test
    public void upToDate() {
        InstallRecord r = managed("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2", LOCAL);
        Catalog c = cat(e("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2"),
                e("Iran/Archive/Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1"));
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, eval(r, c).kind);
    }

    @Test
    public void newerVersionInTheSameFolder() {
        InstallRecord r = managed("Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1", LOCAL);
        Catalog c = cat(e("Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1"),
                e("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.NEWER_VERSION, s.kind);
        assertEquals("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", s.target.path());
    }

    @Test
    public void oldVersionMovedToArchiveAndNewOnePublished() {
        InstallRecord r = managed("Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1", LOCAL);
        Catalog c = cat(e("Iran/Archive/Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1"),
                e("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.NEWER_VERSION, s.kind);
        assertFalse(s.target.isArchived());
    }

    @Test
    public void oldVersionDeletedNewerInSameFolder() {
        InstallRecord r = managed("Iran/Iran_Kinetic_Events_September_7_2026_v1.kmz", "z", LOCAL);
        Catalog c = cat(e("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2"));
        assertEquals(UpdatePlanner.Kind.NEWER_VERSION, eval(r, c).kind);
    }

    @Test
    public void rollingFileChangedInPlace() {
        InstallRecord r = managed("CIP/Kinetic_Activities_Last_30_Days.kmz", "b1", LOCAL);
        Catalog c = cat(e("CIP/Kinetic_Activities_Last_30_Days.kmz", "b2"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.CHANGED, s.kind);
        assertEquals(sha("b2"), s.target.sha());
    }

    @Test
    public void p2RollingFileIsNeverPointedAtAnArchivedSnapshot() {
        InstallRecord r = managed("CIP/Kinetic_Activities_Last_30_Days.kmz", "r1", LOCAL);
        Catalog c = cat(e("CIP/Kinetic_Activities_Last_30_Days.kmz", "r1"),
                e("0_Archive/Kinetic_Activities_Last_30_Days_October_1_2026.kmz", "old"));
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, eval(r, c).kind);
    }

    @Test
    public void movedUnchangedIsRelinkedNotDownloaded() {
        InstallRecord r = managed("Incident Response/China IPB/China_PLA_Centers_of_Gravity_July_4_2026_v2.kmz", "c2", LOCAL);
        Catalog c = cat(e("Global Centers of Gravity/China IPB/China_PLA_Centers_of_Gravity_July_4_2026_v2.kmz", "c2"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.MOVED, s.kind);
        Map<String, String> relinks = UpdatePlanner.relinks(Collections.singletonList(r), c);
        assertEquals("Global Centers of Gravity/China IPB/China_PLA_Centers_of_Gravity_July_4_2026_v2.kmz",
                relinks.get(r.id()));
    }

    @Test
    public void renamedButIdenticalIsTheSameFile() {
        InstallRecord r = managed("Yemen/Yemen FLOT.kmz", "y1", LOCAL);
        assertEquals(UpdatePlanner.Kind.MOVED, eval(r, cat(e("Yemen/Yemen_FLOT_Base.kmz", "y1"))).kind);
    }

    @Test
    public void p1SameNameInAnotherFolderIsOnlyASuggestion() {
        InstallRecord r = managed("Incidents/Spokane Fire/Evacuation_Zones.kmz", "aaa", LOCAL);
        Catalog c = cat(e("Incidents/Reno Fire/Evacuation_Zones.kmz", "bbb"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.GONE, s.kind);
        assertFalse(s.hasUpdate());
        assertEquals("Incidents/Reno Fire/Evacuation_Zones.kmz", s.suggestion.path());
        assertTrue(UpdatePlanner.updatePlan(Collections.singletonList(s)).isEmpty());
    }

    @Test
    public void p1bSameNameElsewhereDoesntAffectAPresentFile() {
        InstallRecord r = managed("A/Evacuation_Zones.kmz", "aaa", LOCAL);
        Catalog c = cat(e("A/Evacuation_Zones.kmz", "aa2"), e("B/Evacuation_Zones.kmz", "bbb"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.CHANGED, s.kind);
        assertEquals("A/Evacuation_Zones.kmz", s.target.path());
    }

    @Test
    public void p10GenericNamesInDifferentIncidentsNeverUpdateEachOther() {
        InstallRecord r = managed("Spokane Fire/Evacuation_Zones_August_8_2026.kmz", "s1", LOCAL);
        Catalog c = cat(e("Spokane Fire/Evacuation_Zones_August_8_2026.kmz", "s1"),
                e("Reno Fire/Evacuation_Zones_August_24_2026.kmz", "r1"));
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, eval(r, c).kind);
    }

    @Test
    public void p3TwoCopiesInDifferentFoldersAreIndependent() {
        InstallRecord a = managed("Yemen 2026/Yemen FLOT.kmz", "y1", LOCAL);
        InstallRecord b = managed("Middle East/Yemen FLOT.kmz", "y2", LOCAL);
        Catalog c = cat(e("Yemen 2026/Yemen FLOT.kmz", "y1b"), e("Middle East/Yemen FLOT.kmz", "y2"));
        List<UpdatePlanner.Status> st = UpdatePlanner.evaluateAll(Arrays.asList(a, b), c);
        assertEquals(UpdatePlanner.Kind.CHANGED, st.get(0).kind);
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, st.get(1).kind);
        Map<String, List<String>> plan = UpdatePlanner.updatePlan(st);
        assertEquals(Collections.singletonList(a.id()), plan.get("Yemen 2026/Yemen FLOT.kmz"));
    }

    @Test
    public void undatedFileDeletedWithADatedOneLeftIsASuggestion() {
        // the real Oct 29 change: "Yemen FLOT.kmz" deleted, "Yemen FLOT_October_10_2026.kmz" kept
        InstallRecord r = managed("Yemen 2026/Yemen FLOT.kmz", "y0", LOCAL);
        Catalog c = cat(e("Yemen 2026/Yemen FLOT_October_10_2026.kmz", "y10"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.GONE, s.kind);
        assertEquals("Yemen 2026/Yemen FLOT_October_10_2026.kmz", s.suggestion.path());
    }

    @Test
    public void p7NewestOnlyInArchiveIsNotAnUpdate() {
        InstallRecord r = managed("Live/X_October_1_2026.kmz", "x1", LOCAL);
        Catalog c = cat(e("Live/X_October_1_2026.kmz", "x1"), e("Live/Archive/X_October_5_2026.kmz", "x2"));
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, eval(r, c).kind);
    }

    @Test
    public void p11KmlAndKmzOfTheSameDateAreNotUpdates() {
        InstallRecord r = managed("UK/UK_Migration_Crisis_February_4_2026.kmz", "k1", LOCAL);
        Catalog c = cat(e("UK/UK_Migration_Crisis_February_4_2026.kmz", "k1"),
                e("UK/UK_Migration_Crisis_February_4_2026.kml", "k2"));
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, eval(r, c).kind);
    }

    @Test
    public void p12IconSetIsNeverUpdatedToAMapLayer() {
        InstallRecord r = managed("Ref/S2U Map Icons.zip", "z1", LOCAL);
        Catalog c = cat(e("Ref/S2U Map Icons.zip", "z1"), e("Ref/S2U_Map_Icons_October_1_2026.kmz", "kz"));
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, eval(r, c).kind);
    }

    @Test
    public void p13PathReusedByADifferentFileFollowsTheContent() {
        InstallRecord r = managed("F/Map.kmz", "m1", LOCAL);
        Catalog c = cat(e("F/Map_Base.kmz", "m1"), e("F/Map.kmz", "m2"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.MOVED, s.kind);
        assertEquals("F/Map_Base.kmz", s.target.path());
    }

    @Test
    public void p14NewerInstalledVersionDeletedFromRepo() {
        InstallRecord older = managed("F/Z_October_1_2026.kmz", "z1", LOCAL);
        InstallRecord newer = managed("F/Z_October_9_2026.kmz", "z9", LOCAL);
        Catalog c = cat(e("F/Z_October_1_2026.kmz", "z1"));
        List<UpdatePlanner.Status> st = UpdatePlanner.evaluateAll(Arrays.asList(older, newer), c);
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, st.get(0).kind);
        assertEquals(UpdatePlanner.Kind.GONE, st.get(1).kind);
        assertTrue(UpdatePlanner.updatePlan(st).isEmpty());
    }

    @Test
    public void supersededWhenTheNewerOneIsInstalledToo() {
        InstallRecord old = managed("F/Z_October_1_2026.kmz", "z1", LOCAL);
        InstallRecord cur = managed("F/Z_October_9_2026.kmz", "z9", LOCAL);
        Catalog c = cat(e("F/Z_October_1_2026.kmz", "z1"), e("F/Z_October_9_2026.kmz", "z9"));
        assertEquals(UpdatePlanner.Kind.SUPERSEDED, eval(old, c, cur).kind);
    }

    @Test
    public void legacyRecordsAreNeverPlanned() {
        InstallRecord r = legacy("Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1", LOCAL);
        Catalog c = cat(e("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2"));
        UpdatePlanner.Status s = eval(r, c);
        assertEquals(UpdatePlanner.Kind.UNMANAGED, s.kind);
        assertTrue(UpdatePlanner.relinks(Collections.singletonList(r), c).isEmpty());
    }

    @Test
    public void updatePlanSharesOneDownloadAndReplacesOnlyPlannedRecords() {
        InstallRecord a = managed("Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1", LOCAL);
        InstallRecord b = managed("Iran/Iran_Kinetic_Events_June_1_2026_v1.kmz", "a0", LOCAL);
        InstallRecord other = managed("Spokane/Evac_August_8_2026.kmz", "s1", LOCAL);
        Catalog c = cat(e("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2"),
                e("Spokane/Evac_August_8_2026.kmz", "s1"), e("Reno/Evac_August_24_2026.kmz", "r1"));
        Map<String, List<String>> plan = UpdatePlanner.updatePlan(
                UpdatePlanner.evaluateAll(Arrays.asList(a, b, other), c));
        assertEquals(1, plan.size());
        assertEquals(Arrays.asList(a.id(), b.id()), plan.get("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz"));
    }

    @Test
    public void relinkSkipsAPathAnotherRecordHolds() {
        InstallRecord a = managed("Old/X.kmz", "x", LOCAL);
        InstallRecord b = managed("New/X.kmz", "x", LOCAL);
        Catalog c = cat(e("New/X.kmz", "x"));
        assertTrue(UpdatePlanner.relinks(Arrays.asList(a, b), c).isEmpty());
        assertNull(UpdatePlanner.relinks(Collections.singletonList(a), cat()).get(a.id()));
    }
}
