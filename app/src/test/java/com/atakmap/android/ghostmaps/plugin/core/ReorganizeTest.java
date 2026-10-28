package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * What happens to installed files when the repo is reorganized: folders
 * renamed, files moved, archived, renamed or changed in place.
 */
public class ReorganizeTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private InstalledStore store;
    private String local;

    @Before
    public void setUp() throws Exception {
        store = new InstalledStore(tmp.newFile("installed.json"));
        local = tmp.newFile("x.kmz").getPath();
    }

    private static Catalog repo(RepoFile... files) {
        return new Catalog(Arrays.asList(files));
    }

    private InstalledFile install(String path, String sha) throws Exception {
        InstalledFile f = new InstalledFile(path, sha, 1, local, "KML", "", 1L);
        store.put(f);
        return f;
    }

    @Test
    public void folderRenamedFileUnchangedIsRelinkedWithoutDownload() throws Exception {
        InstalledFile inst = install("Incident Response/Yemen 2026/Yemen FLOT_October_10_2026.kmz", "aa");
        Catalog after = repo(new RepoFile("Middle East/Yemen/Yemen FLOT_October_10_2026.kmz", "aa", 1));

        UpdateCheck.Status s = UpdateCheck.check(inst, after, store);
        assertEquals(UpdateCheck.Kind.MOVED, s.kind);
        assertFalse(s.hasUpdate());

        assertEquals(1, UpdateCheck.relinkMoved(store, after));
        assertTrue(store.isInstalled("Middle East/Yemen/Yemen FLOT_October_10_2026.kmz"));
        assertFalse(store.isInstalled(inst.getRepoPath()));
        InstalledFile relinked = store.get("Middle East/Yemen/Yemen FLOT_October_10_2026.kmz");
        assertEquals(local, relinked.getLocalPath()); // same file on the device
        assertEquals(UpdateCheck.Kind.UP_TO_DATE, UpdateCheck.check(relinked, after, store).kind);
    }

    @Test
    public void fileRenamedButIdenticalIsStillTheSameFile() throws Exception {
        InstalledFile inst = install("Yemen 2026/Yemen FLOT.kmz", "bb");
        Catalog after = repo(new RepoFile("Yemen 2026/Yemen_FLOT_Base.kmz", "bb", 1));
        assertEquals(UpdateCheck.Kind.MOVED, UpdateCheck.check(inst, after, store).kind);
    }

    @Test
    public void movedAndChangedOffersAnUpdateThatReplacesTheOldInstall() throws Exception {
        InstalledFile inst = install("30-Day Pulls/Kinetic_Activities_Last_30_Days.kmz", "c1");
        Catalog after = repo(new RepoFile("CIP/Rolling/Kinetic_Activities_Last_30_Days.kmz", "c2", 1));

        UpdateCheck.Status s = UpdateCheck.check(inst, after, store);
        assertEquals(UpdateCheck.Kind.CHANGED, s.kind);
        assertEquals("CIP/Rolling/Kinetic_Activities_Last_30_Days.kmz", s.target.getPath());
        List<InstalledFile> replaced = UpdateCheck.replacedBy(s.target, true, store, after);
        assertEquals(1, replaced.size());
        assertEquals(inst.getRepoPath(), replaced.get(0).getRepoPath());
    }

    @Test
    public void oldVersionArchivedNewVersionElsewhere() throws Exception {
        InstalledFile inst = install("CIP/S2Underground_Common_Intelligence_Picture_October_23_2026_Export_1.kmz", "d1");
        Catalog after = repo(
                new RepoFile("0_Archive/CIP/S2Underground_Common_Intelligence_Picture_October_23_2026_Export_1.kmz", "d1", 1),
                new RepoFile("CIP/2026/October/S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz", "d2", 1));
        UpdateCheck.Status s = UpdateCheck.check(inst, after, store);
        assertEquals(UpdateCheck.Kind.NEWER_VERSION, s.kind);
        assertTrue(s.target.getPath().endsWith("October_27_2026_Export_1.kmz"));
    }

    @Test
    public void dailyFilesSplitIntoMonthFoldersStayOneProduct() {
        Catalog c = repo(
                new RepoFile("CIP/2026-09/S2Underground_Common_Intelligence_Picture_September_30_2026_Export_1.kmz", "e1", 1),
                new RepoFile("CIP/2026-10/S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz", "e2", 1),
                new RepoFile("CIP/2026-10/S2Underground_Common_Intelligence_Picture_October_1_2026_Export_1.kmz", "e3", 1));
        List<RepoFile> latest = c.latest(null);
        assertEquals(1, latest.size());
        assertEquals("e2", latest.get(0).getSha());
        assertEquals(3, c.versionsOf(latest.get(0)).size());
    }

    @Test
    public void wholeProductArchivedKeepsInstallAndLeavesLatest() throws Exception {
        InstalledFile inst = install("Incidents/Texas Floods/TexasFloods_July_2025_v1.kmz", "f1");
        Catalog after = repo(
                new RepoFile("0_Archive/Texas Floods - July, 2025/TexasFloods_July_2025_v1.kmz", "f1", 1),
                new RepoFile("Incidents/Other/Other_October_1_2026.kmz", "zz", 1));
        UpdateCheck.relinkMoved(store, after);
        InstalledFile relinked = store.get("0_Archive/Texas Floods - July, 2025/TexasFloods_July_2025_v1.kmz");
        assertEquals(UpdateCheck.Kind.UP_TO_DATE, UpdateCheck.check(relinked, after, store).kind);
        assertEquals(1, after.latest(null).size()); // archived product not in Latest
        assertTrue(after.latest(null).get(0).getPath().startsWith("Incidents/Other"));
    }

    @Test
    public void archivedCopyNeverWinsATieAgainstTheLiveOne() {
        Catalog c = repo(
                new RepoFile("0_Archive/Yemen FLOT.kmz", "g1", 1),
                new RepoFile("Yemen 2026/Yemen FLOT.kmz", "g2", 1));
        assertEquals("Yemen 2026/Yemen FLOT.kmz", c.latest(null).get(0).getPath());
    }

    @Test
    public void deletedWithNoSuccessorIsReportedNotRemoved() throws Exception {
        InstalledFile inst = install("Garden Grove/Garden Grove Evac Zone1.kmz", "h1");
        Catalog after = repo(new RepoFile("Other/Something_October_1_2026.kmz", "zz", 1));
        UpdateCheck.Status s = UpdateCheck.check(inst, after, store);
        assertEquals(UpdateCheck.Kind.REMOVED_FROM_REPO, s.kind);
        assertEquals(0, UpdateCheck.relinkMoved(store, after));
        assertTrue(store.isInstalled(inst.getRepoPath())); // left alone
    }

    @Test
    public void browseInstallOfAnOlderVersionReplacesNothingNewer() throws Exception {
        install("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "i2");
        RepoFile older = new RepoFile("Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz", "i1", 1);
        Catalog c = repo(older, new RepoFile("Iran/Iran_Kinetic_Events_October_15_2026_v1.kmz", "i2", 1));
        assertTrue(UpdateCheck.replacedBy(older, false, store, c).isEmpty());
        // and an update to the newest replaces every older install of the product
        store.put(new InstalledFile("Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz", "i1", 1, local, "KML", "", 1L));
        store.put(new InstalledFile("Iran/Iran_Kinetic_Events_June_1_2026_v1.kmz", "i0", 1, local, "KML", "", 1L));
        RepoFile newer = new RepoFile("Iran/Iran_Kinetic_Events_October_20_2026_v1.kmz", "i3", 1);
        Catalog c2 = repo(newer);
        assertEquals(3, UpdateCheck.replacedBy(newer, true, store, c2).size());
    }

    @Test
    public void reinstallingTheSameChangedFileReplacesItsOldRecord() throws Exception {
        install("30-Day Pulls/Kinetic_Activities_Last_30_Days.kmz", "k1");
        RepoFile now = new RepoFile("30-Day Pulls/Kinetic_Activities_Last_30_Days.kmz", "k2", 1);
        assertEquals(1, UpdateCheck.replacedBy(now, false, store, repo(now)).size());
    }

    @Test
    public void productNamesIgnoreCasePunctuationAndFolders() {
        RepoFile a = new RepoFile("A/Iran_Kinetic_Events_October_10_2026_v2.kmz", "", 1);
        RepoFile b = new RepoFile("B/c/Iran Kinetic-Events July 8, 2026.kmz", "", 1);
        assertEquals(a.productKey(), b.productKey());
        assertEquals("iran kinetic events", a.productKey());
    }

    @Test
    public void uniqueLocalNames() {
        HashSet<String> taken = new HashSet<>(Arrays.asList("yemen flot.kmz", "yemen flot (2).kmz"));
        assertEquals("Yemen FLOT (3).kmz", LocalNames.unique("Yemen FLOT.kmz", taken));
        assertEquals("Other.kmz", LocalNames.unique("Other.kmz", taken));
        assertEquals("a_b.kmz", LocalNames.unique("a:b.kmz", Collections.<String>emptySet()));
    }

    @Test
    public void otherSourcesMatchByProduct() {
        OtherSources o = new OtherSources(Arrays.asList(
                "/sdcard/atak/tools/datasync/CIP/S2Underground_Common_Intelligence_Picture_October_23_2026_Export_1.kmz",
                "/sdcard/atak/overlays/unrelated.kml"));
        RepoFile cip = new RepoFile("X/S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz", "", 1);
        assertEquals(1, o.forProduct(cip).size());
        assertTrue(o.forProduct(new RepoFile("X/Yemen FLOT.kmz", "", 1)).isEmpty());
        assertTrue(OtherSources.NONE.isEmpty());
        new File(local).delete();
    }
}
