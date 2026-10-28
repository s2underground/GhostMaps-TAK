package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Arrays;
import java.util.List;

public class UpdateCheckTest {

    private static final String F = "Data/Iran/";

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private Catalog catalog;
    private InstalledStore store;
    private File local;

    @Before
    public void setUp() throws Exception {
        catalog = new Catalog(Arrays.asList(
                new RepoFile(F + "Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1", 10),
                new RepoFile(F + "Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2", 20),
                new RepoFile(F + "Kinetic_Activities_Last_30_Days.kmz", "b2", 30),
                new RepoFile(F + "Yemen FLOT.kmz", "c1", 5)));
        store = new InstalledStore(tmp.newFile("installed.json"));
        local = tmp.newFile("x.kmz");
    }

    private InstalledFile installed(String path, String sha) {
        return new InstalledFile(F + path, sha, 1, local.getPath(), "KML",
                "application/vnd.google-earth.kmz", 1L);
    }

    @Test
    public void upToDate() {
        UpdateCheck.Status s = UpdateCheck.check(
                installed("Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2"), catalog, store);
        assertEquals(UpdateCheck.Kind.UP_TO_DATE, s.kind);
        assertFalse(s.hasUpdate());
    }

    @Test
    public void newerDatedVersion() {
        UpdateCheck.Status s = UpdateCheck.check(
                installed("Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1"), catalog, store);
        assertEquals(UpdateCheck.Kind.NEWER_VERSION, s.kind);
        assertEquals(F + "Iran_Kinetic_Events_October_15_2026_v1.kmz", s.target.getPath());
    }

    @Test
    public void newerVersionAlreadyInstalledMeansSuperseded() throws Exception {
        store.put(installed("Iran_Kinetic_Events_October_15_2026_v1.kmz", "a2"));
        UpdateCheck.Status s = UpdateCheck.check(
                installed("Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1"), catalog, store);
        assertEquals(UpdateCheck.Kind.SUPERSEDED, s.kind);
        assertFalse(s.hasUpdate());
    }

    @Test
    public void rollingFileChangedInPlace() {
        UpdateCheck.Status s = UpdateCheck.check(
                installed("Kinetic_Activities_Last_30_Days.kmz", "b1"), catalog, store);
        assertEquals(UpdateCheck.Kind.CHANGED, s.kind);
        assertEquals("b2", s.target.getSha());
    }

    @Test
    public void deletedFromRepoButNewerExists() {
        // an installed file GitHub no longer lists, with a newer one in its place
        UpdateCheck.Status s = UpdateCheck.check(
                installed("Iran_Kinetic_Events_September_7_2026_v1.kmz", "z"), catalog, store);
        assertEquals(UpdateCheck.Kind.NEWER_VERSION, s.kind);
    }

    @Test
    public void removedFromRepo() {
        UpdateCheck.Status s = UpdateCheck.check(
                installed("Something_Else_June_1_2026.kmz", "z"), catalog, store);
        assertEquals(UpdateCheck.Kind.REMOVED_FROM_REPO, s.kind);
        assertNull(s.target);
    }

    @Test
    public void countsOneUpdatePerTarget() throws Exception {
        store.put(installed("Iran_Kinetic_Events_October_10_2026_v1.kmz", "a1"));
        store.put(new InstalledFile(F + "Iran_Kinetic_Events_June_1_2026_v1.kmz", "q", 1,
                local.getPath(), "KML", "", 1L));
        store.put(installed("Kinetic_Activities_Last_30_Days.kmz", "b1"));
        List<UpdateCheck.Status> all = UpdateCheck.checkAll(store, catalog);
        assertEquals(3, all.size());
        // two old Iran files point at the same newest file: one download
        assertEquals(2, UpdateCheck.countUpdates(all));
    }

    @Test
    public void storeRoundTripAndPrune() throws Exception {
        File f = new File(tmp.getRoot(), "sub/installed.json");
        InstalledStore s1 = new InstalledStore(f);
        s1.put(installed("Yemen FLOT.kmz", "c1"));
        s1.put(new InstalledFile(F + "gone.kmz", "d", 1,
                new File(tmp.getRoot(), "missing.kmz").getPath(), "KML", "", 2L));
        InstalledStore s2 = new InstalledStore(f);
        assertEquals(2, s2.size());
        assertEquals("c1", s2.get(F + "Yemen FLOT.kmz").getSha());
        assertEquals(1, s2.pruneMissing());
        assertTrue(new InstalledStore(f).isInstalled(F + "Yemen FLOT.kmz"));
        assertFalse(new InstalledStore(f).isInstalled(F + "gone.kmz"));
    }

    @Test
    public void corruptStoreStartsEmpty() throws Exception {
        File f = tmp.newFile("bad.json");
        java.nio.file.Files.write(f.toPath(), "{not json".getBytes("UTF-8"));
        assertEquals(0, new InstalledStore(f).size());
    }
}
