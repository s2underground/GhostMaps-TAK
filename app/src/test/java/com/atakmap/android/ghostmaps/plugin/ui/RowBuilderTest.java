package com.atakmap.android.ghostmaps.plugin.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.ghostmaps.plugin.core.Catalog;
import com.atakmap.android.ghostmaps.plugin.core.FileKind;
import com.atakmap.android.ghostmaps.plugin.core.Fixtures;
import com.atakmap.android.ghostmaps.plugin.core.InstallRecord;
import com.atakmap.android.ghostmaps.plugin.core.InstallStore;
import com.atakmap.android.ghostmaps.plugin.core.OtherSources;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.core.UpdatePlanner;
import com.atakmap.android.ghostmaps.plugin.engine.Snapshot;
import com.atakmap.android.ghostmaps.plugin.net.RepoSource;
import com.atakmap.android.ghostmaps.plugin.ui.RowBuilder.Action;
import com.atakmap.android.ghostmaps.plugin.ui.RowBuilder.Row;
import com.atakmap.android.ghostmaps.plugin.ui.RowBuilder.RowType;
import com.atakmap.android.ghostmaps.plugin.ui.RowBuilder.Status;
import com.atakmap.android.ghostmaps.plugin.ui.RowBuilder.Tab;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** What every panel row says and offers, for every state. */
public class RowBuilderTest {

    private static final String DIR = "/sdcard/atak/GhostMaps/";
    private static final RepoEntry V1 = Fixtures.e("Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz", "v1");
    private static final RepoEntry V2 = Fixtures.e("Iran/Iran_Kinetic_Events_October_12_2026_v1.kmz", "v2");
    private static final RepoEntry ICONS = Fixtures.e("Reference/S2U Map Icons.zip", "icons");
    private static final RepoEntry LITANI = Fixtures.e("Lebanon/Litani_River_August_1_2026.kmz", "lit");

    /** Builds snapshots the way the engine does. */
    private static final class S {
        Catalog catalog = Fixtures.cat(V1, V2, ICONS, LITANI);
        final List<InstallRecord> records = new ArrayList<>();
        final Map<InstallRecord, Snapshot.Presence> presence = new LinkedHashMap<>();
        final Map<String, Snapshot.JobView> jobs = new LinkedHashMap<>();
        OtherSources others = OtherSources.NONE;

        InstallRecord add(RepoEntry e, Snapshot.Presence p) {
            InstallRecord r = new InstallRecord(InstallStore.newId(), e.path(), e.sha(), e.size(), e.kind(),
                    new File(DIR + e.fileName()).getAbsolutePath(), e.kind() == FileKind.ICON_SET ? "u" : null,
                    p == Snapshot.Presence.UNMANAGED ? InstallRecord.Origin.LEGACY : InstallRecord.Origin.GHOSTMAPS,
                    InstallRecord.State.ACTIVE, 1, 1, null);
            records.add(r);
            presence.put(r, p);
            return r;
        }

        S job(RepoEntry e, Snapshot.JobPhase phase, int pct, String err) {
            jobs.put(e.path(), new Snapshot.JobView(e, phase, pct, err, false));
            return this;
        }

        Snapshot snap() {
            Set<String> shas = new HashSet<>();
            for (InstallRecord r : records)
                if (r.isManaged())
                    shas.add(r.sha());
            List<Snapshot.RecordView> views = new ArrayList<>();
            List<UpdatePlanner.Status> st = new ArrayList<>();
            for (InstallRecord r : records) {
                UpdatePlanner.Status s = catalog == null ? null : UpdatePlanner.evaluate(r, catalog, shas);
                if (s != null)
                    st.add(s);
                views.add(new Snapshot.RecordView(r, presence.get(r), s));
            }
            RepoSource.Listing l = catalog == null ? null : new RepoSource.Listing(catalog, 1, null, false);
            return new Snapshot(l, false, null, views, jobs, others, UpdatePlanner.countUpdates(st));
        }
    }

    private static Row entry(Snapshot s, RepoEntry e) {
        return RowBuilder.entryRow(s, e, 0, false);
    }

    private static Row installed(Snapshot s, RepoEntry e) {
        for (Row r : RowBuilder.build(s, Tab.INSTALLED, "", ""))
            if (r.type == RowType.RECORD && r.record.record.repoPath().equals(e.path()))
                return r;
        throw new AssertionError("no installed row for " + e.path());
    }

    // ------------------------------------------------------------ entry rows

    @Test
    public void notInstalledOffersInstall() {
        Row r = entry(new S().snap(), LITANI);
        assertEquals(Status.NONE, r.status);
        assertEquals(Action.INSTALL, r.primary);
        assertEquals(Collections.singletonList(Action.INSTALL), r.menu);
    }

    @Test
    public void loadedMapLayerOffersViewNeverRemoveOnTap() {
        S s = new S();
        s.add(LITANI, Snapshot.Presence.ACTIVE);
        Row r = entry(s.snap(), LITANI);
        assertEquals(Status.ON_MAP, r.status);
        assertEquals(RowBuilder.Tone.GOOD, r.tone);
        assertEquals(Action.VIEW, r.primary);
        assertEquals(Arrays.asList(Action.VIEW, Action.REMOVE), r.menu);
    }

    @Test
    public void installedIconSetIsNeverDescribedAsAMapLayer() {
        S s = new S();
        s.add(ICONS, Snapshot.Presence.ACTIVE);
        Row r = entry(s.snap(), ICONS);
        assertEquals(Status.ICONS_INSTALLED, r.status);
        assertEquals(Action.NONE, r.primary);
        assertEquals(Collections.singletonList(Action.REMOVE), r.menu);
        assertTrue(r.isIconSet());

        for (Snapshot.Presence p : new Snapshot.Presence[] { Snapshot.Presence.LOADING,
                Snapshot.Presence.NOT_LOADED }) {
            S t = new S();
            t.add(ICONS, p);
            Row row = installed(t.snap(), ICONS);
            assertNotEquals(Status.ON_MAP, row.status);
            assertNotEquals(Status.NOT_LOADED, row.status);
            assertNotEquals(Status.LOADING, row.status);
            assertNotEquals(Action.VIEW, row.primary);
            assertNotEquals(Action.RELOAD, row.primary);
            assertFalse(row.menu.contains(Action.VIEW));
        }
        S t = new S().job(ICONS, Snapshot.JobPhase.WAITING, 100, null);
        assertEquals(Status.ICONS_ADDING, entry(t.snap(), ICONS).status);
    }

    @Test
    public void iconSetMissingFromAtakOffersAddToAtak() {
        S s = new S();
        s.add(ICONS, Snapshot.Presence.NOT_LOADED);
        Row r = installed(s.snap(), ICONS);
        assertEquals(Status.ICONS_NOT_ADDED, r.status);
        assertEquals(Action.ADD_ICONS, r.primary);
    }

    @Test
    public void mapLayerAtakLostOffersReload() {
        S s = new S();
        s.add(LITANI, Snapshot.Presence.NOT_LOADED);
        Row r = installed(s.snap(), LITANI);
        assertEquals(Status.NOT_LOADED, r.status);
        assertEquals(Action.RELOAD, r.primary);
        assertTrue(r.menu.contains(Action.REMOVE));
    }

    @Test
    public void jobPhases() {
        Row q = entry(new S().job(LITANI, Snapshot.JobPhase.QUEUED, 0, null).snap(), LITANI);
        assertEquals(Status.QUEUED, q.status);
        assertEquals(Action.CANCEL, q.primary);

        Row d = entry(new S().job(LITANI, Snapshot.JobPhase.DOWNLOADING, 42, null).snap(), LITANI);
        assertEquals(Status.DOWNLOADING, d.status);
        assertEquals(42, d.progress);
        assertEquals("42%", d.statusArg);
        assertTrue(d.primaryEnabled);

        for (Snapshot.JobPhase p : new Snapshot.JobPhase[] { Snapshot.JobPhase.INSTALLING,
                Snapshot.JobPhase.WAITING }) {
            Row r = entry(new S().job(LITANI, p, 100, null).snap(), LITANI);
            assertEquals(-2, r.progress);
            assertFalse("too late to cancel safely", r.primaryEnabled);
            assertTrue(r.menu.isEmpty());
        }

        Row f = entry(new S().job(LITANI, Snapshot.JobPhase.FAILED, 0, "Network down").snap(), LITANI);
        assertEquals(Status.FAILED, f.status);
        assertEquals("Network down", f.statusArg);
        assertEquals(RowBuilder.Tone.PROBLEM, f.tone);
        assertEquals(Action.RETRY, f.primary);
        assertEquals(Arrays.asList(Action.RETRY, Action.DISMISS), f.menu);
    }

    @Test
    public void newerVersionIsOfferedOnBothRows() {
        S s = new S();
        s.add(V1, Snapshot.Presence.ACTIVE);
        Snapshot snap = s.snap();
        assertEquals(1, snap.updateCount);

        Row rec = installed(snap, V1);
        assertEquals(Status.UPDATE_NEWER, rec.status);
        assertEquals("Oct 12, 2026", rec.statusArg); // "v1" is implied
        assertEquals(Action.UPDATE, rec.primary);
        assertEquals(Arrays.asList(Action.UPDATE, Action.VIEW, Action.REMOVE), rec.menu);

        Row newer = entry(snap, V2);
        assertEquals(Status.UPDATE_NEWER, newer.status);
        assertEquals(Action.UPDATE, newer.primary);
        assertSame("the update replaces the installed version", snap.records.get(0), newer.record);
        assertSame(V2, newer.entry);
    }

    @Test
    public void olderVersionWhileNewerIsInstalled() {
        S s = new S();
        s.add(V2, Snapshot.Presence.ACTIVE);
        Row r = entry(s.snap(), V1);
        assertEquals(Status.OTHER_VERSION_INSTALLED, r.status);
        assertEquals(Action.INSTALL, r.primary);
    }

    @Test
    public void fileChangedAtTheSamePath() {
        S s = new S();
        InstallRecord old = new InstallRecord(InstallStore.newId(), LITANI.path(), Fixtures.sha("old"), 5,
                FileKind.MAP_LAYER, DIR + LITANI.fileName(), null, InstallRecord.Origin.GHOSTMAPS,
                InstallRecord.State.ACTIVE, 1, 1, null);
        s.records.add(old);
        s.presence.put(old, Snapshot.Presence.ACTIVE);
        Snapshot snap = s.snap();
        Row e = entry(snap, LITANI);
        assertEquals(Status.UPDATE_CHANGED, e.status);
        assertEquals(Action.UPDATE, e.primary);
        assertEquals(Status.UPDATE_CHANGED, installed(snap, LITANI).status);
    }

    @Test
    public void alreadyLoadedFromDataSyncIsPointedOut() {
        S s = new S();
        s.others = new OtherSources(Collections.singletonList(
                "/sdcard/atak/tools/datasync/Litani_River_July_1_2026.kmz"));
        Row r = entry(s.snap(), LITANI);
        assertEquals(Status.ALSO_ELSEWHERE, r.status);
        assertEquals(Action.INSTALL, r.primary);
    }

    @Test
    public void earlyTestBuildRecordsOfferForgetOnly() {
        S s = new S();
        s.add(LITANI, Snapshot.Presence.UNMANAGED);
        for (Row r : Arrays.asList(entry(s.snap(), LITANI), installed(s.snap(), LITANI))) {
            assertEquals(Status.UNMANAGED, r.status);
            assertEquals(Action.FORGET, r.primary);
            assertFalse(r.menu.contains(Action.REMOVE));
            assertFalse(r.menu.contains(Action.UPDATE));
        }
    }

    @Test
    public void deletedFromGitHubWithASimilarFile() {
        S s = new S();
        RepoEntry gone = Fixtures.e("Old/Litani_River_August_1_2026.kmz", "gone");
        s.add(gone, Snapshot.Presence.ACTIVE);
        Row r = installed(s.snap(), gone);
        assertEquals(Status.GONE, r.status);
        assertEquals(Action.REPLACE, r.primary);
        assertEquals(LITANI.path(), r.suggestion.path());
        assertTrue(r.menu.contains(Action.REMOVE));
        assertTrue(r.menu.contains(Action.VIEW));
    }

    @Test
    public void deletedFromGitHubWithNothingSimilar() {
        S s = new S();
        RepoEntry gone = Fixtures.e("Old/Yemen FLOT.kmz", "y");
        s.add(gone, Snapshot.Presence.ACTIVE);
        Row r = installed(s.snap(), gone);
        assertEquals(Status.GONE, r.status);
        assertEquals(Action.NONE, r.primary);
        assertTrue(r.menu.contains(Action.REMOVE));
    }

    @Test
    public void bothVersionsInstalledMarksTheOldOneRedundant() {
        S s = new S();
        s.add(V1, Snapshot.Presence.ACTIVE);
        s.add(V2, Snapshot.Presence.ACTIVE);
        Row r = installed(s.snap(), V1);
        assertEquals(Status.SUPERSEDED, r.status);
        assertEquals(Action.REMOVE, r.primary); // the pane only runs REMOVE after confirmation
    }

    @Test
    public void removingShowsProgressAndNoActions() {
        S s = new S();
        s.add(LITANI, Snapshot.Presence.REMOVING);
        Row r = installed(s.snap(), LITANI);
        assertEquals(Status.REMOVING, r.status);
        assertEquals(-2, r.progress);
        assertTrue(r.menu.isEmpty());
    }

    // ------------------------------------------------------------- tabs

    @Test
    public void installedTabSplitsMapsAndIconsAndPutsAttentionFirst() {
        S s = new S();
        s.add(LITANI, Snapshot.Presence.ACTIVE);
        s.add(ICONS, Snapshot.Presence.ACTIVE);
        s.add(V1, Snapshot.Presence.ACTIVE); // has an update
        List<Row> rows = RowBuilder.build(s.snap(), Tab.INSTALLED, "", "");
        assertEquals(RowType.SECTION, rows.get(0).type);
        assertEquals(RowBuilder.Section.MAP_LAYERS, rows.get(0).section);
        assertEquals(V1.path(), rows.get(1).record.record.repoPath());
        assertEquals(LITANI.path(), rows.get(2).record.record.repoPath());
        assertEquals(RowBuilder.Section.ICON_SETS, rows.get(3).section);
        assertEquals(ICONS.path(), rows.get(4).record.record.repoPath());

        S onlyMaps = new S();
        onlyMaps.add(LITANI, Snapshot.Presence.ACTIVE);
        assertEquals(1, RowBuilder.build(onlyMaps.snap(), Tab.INSTALLED, "", "").size());

        assertEquals(1, RowBuilder.build(s.snap(), Tab.INSTALLED, "", "litani river").size()); // maps alone: no header
    }

    @Test
    public void installedTabWorksWithoutAnyListing() {
        S s = new S();
        s.catalog = null; // first run offline after a reinstall of the plugin
        s.add(LITANI, Snapshot.Presence.ACTIVE);
        Row r = RowBuilder.build(s.snap(), Tab.INSTALLED, "", "").get(0);
        assertEquals(Status.ON_MAP, r.status);
        assertTrue(RowBuilder.build(s.snap(), Tab.LATEST, "", "").isEmpty());
        assertTrue(RowBuilder.build(s.snap(), Tab.BROWSE, "", "").isEmpty());
    }

    @Test
    public void browseShowsFoldersThenFilesAndSurvivesVanishedFolders() throws Exception {
        S s = new S();
        s.catalog = Fixtures.real(Fixtures.MORNING_OCT_29);
        Snapshot snap = s.snap();
        List<Row> top = RowBuilder.build(snap, Tab.BROWSE, "", "");
        assertEquals(RowType.FOLDER, top.get(0).type);
        String folder = top.get(0).folder;
        List<Row> inside = RowBuilder.build(snap, Tab.BROWSE, folder, "");
        assertEquals(RowType.UP, inside.get(0).type);
        assertEquals(folder, RowBuilder.existingFolder(snap.catalog(), folder));
        assertEquals(folder, RowBuilder.existingFolder(snap.catalog(), folder + "/Deleted/Deeper"));
        assertEquals("", RowBuilder.existingFolder(snap.catalog(), "Nope/Gone"));
    }

    /** Invariants over every row the real repository produces, in every state. */
    @Test
    public void invariantsAcrossTheRealRepository() throws Exception {
        Catalog c = Fixtures.real(Fixtures.MORNING_OCT_29);
        Snapshot.Presence[] ps = Snapshot.Presence.values();
        Snapshot.JobPhase[] js = Snapshot.JobPhase.values();
        S s = new S();
        s.catalog = c;
        int i = 0;
        for (RepoEntry e : c.latest(null)) {
            if (i % 3 == 0)
                s.add(e, ps[i % ps.length]);
            else if (i % 3 == 1)
                s.job(e, js[i % js.length], 50, "x");
            i++;
        }
        Snapshot snap = s.snap();
        List<Row> rows = new ArrayList<>();
        for (Tab t : Tab.values())
            rows.addAll(RowBuilder.build(snap, t, "", ""));
        assertTrue(rows.size() > 30);
        for (Row r : rows) {
            if (r.type != RowType.ENTRY && r.type != RowType.RECORD)
                continue;
            assertNotNull(r.entry);
            assertEquals("no duplicate menu items", new HashSet<>(r.menu).size(), r.menu.size());
            if (!r.primaryEnabled)
                assertFalse(r.menu.contains(r.primary));
            boolean active = r.record != null && r.record.presence == Snapshot.Presence.ACTIVE;
            if (active && r.status != Status.SUPERSEDED)
                assertNotEquals("a working layer is never one tap from deletion", Action.REMOVE, r.primary);
            if (r.isIconSet()) {
                assertNotEquals(Action.VIEW, r.primary);
                assertNotEquals(Action.RELOAD, r.primary);
                assertFalse(r.menu.contains(Action.VIEW));
            }
            if (r.record != null && !r.record.record.isManaged()) {
                assertFalse(r.menu.contains(Action.REMOVE));
                assertFalse(r.menu.contains(Action.UPDATE));
            }
        }
    }

    @Test
    public void recordWhoseInstallIsRunningOffersNothingDestructive() {
        S s = new S();
        s.add(V2, Snapshot.Presence.LOADING);
        s.job(V2, Snapshot.JobPhase.WAITING, 100, null);
        Row r = installed(s.snap(), V2);
        assertEquals(Status.WAITING, r.status);
        assertTrue(r.menu.isEmpty());
        assertFalse(r.primaryEnabled);
    }

    @Test
    public void replacementInProgressShowsOnTheGoneRow() {
        S s = new S();
        RepoEntry gone = Fixtures.e("Old/Litani_River_August_1_2026.kmz", "gone");
        s.add(gone, Snapshot.Presence.ACTIVE);
        s.job(LITANI, Snapshot.JobPhase.DOWNLOADING, 30, null);
        Row r = installed(s.snap(), gone);
        assertEquals(Status.DOWNLOADING, r.status);
        assertEquals(30, r.progress);
        assertFalse(r.menu.contains(Action.REPLACE));
    }

    @Test
    public void removeIsAVisibleSecondButtonNeverTheRowTap() {
        S s = new S();
        s.add(LITANI, Snapshot.Presence.ACTIVE);
        s.add(ICONS, Snapshot.Presence.ACTIVE);
        s.add(V1, Snapshot.Presence.ACTIVE); // has an update
        Snapshot snap = s.snap();

        Row map = installed(snap, LITANI);
        assertEquals(Action.VIEW, map.primary);
        assertEquals(Action.REMOVE, map.secondary);

        Row icons = installed(snap, ICONS);
        assertEquals(Action.NONE, icons.primary);
        assertEquals(Action.REMOVE, icons.secondary);

        Row update = installed(snap, V1);
        assertEquals(Action.UPDATE, update.primary);
        assertEquals(Action.REMOVE, update.secondary);

        assertEquals(Action.REMOVE, entry(snap, LITANI).secondary); // Latest/Browse too
        assertEquals(Action.NONE, entry(snap, Fixtures.e("X/New_Thing.kmz", "n")).secondary);
    }

    @Test
    public void noSecondButtonWhereRemoveIsWrongOrAlreadyPrimary() {
        S legacy = new S();
        legacy.add(LITANI, Snapshot.Presence.UNMANAGED);
        assertEquals(Action.NONE, installed(legacy.snap(), LITANI).secondary);

        S both = new S();
        both.add(V1, Snapshot.Presence.ACTIVE);
        both.add(V2, Snapshot.Presence.ACTIVE);
        Row superseded = installed(both.snap(), V1);
        assertEquals(Action.REMOVE, superseded.primary);
        assertEquals(Action.NONE, superseded.secondary);

        S busy = new S();
        busy.add(V2, Snapshot.Presence.LOADING);
        busy.job(V2, Snapshot.JobPhase.WAITING, 100, null);
        assertEquals(Action.NONE, installed(busy.snap(), V2).secondary);

        S removing = new S();
        removing.add(LITANI, Snapshot.Presence.REMOVING);
        assertEquals(Action.NONE, installed(removing.snap(), LITANI).secondary);
    }
}
