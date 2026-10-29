package com.atakmap.android.ghostmaps.plugin.engine;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.atakmap.android.ghostmaps.plugin.core.FileKind;
import com.atakmap.android.ghostmaps.plugin.core.InstallRecord;
import com.atakmap.android.ghostmaps.plugin.core.InstallStore;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;
import com.atakmap.android.ghostmaps.plugin.core.UpdatePlanner;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * The engine end to end, with a fake ATAK and repository. Each test is one
 * situation the plugin must survive on a real device.
 */
public class GhostMapsEngineTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private EngineHarness h;

    private static final String V1 = "Iran/Iran_Kinetic_Events_October_10_2026_v1.kmz";
    private static final String V2 = "Iran/Iran_Kinetic_Events_October_12_2026_v1.kmz";
    private static final String ICONS = "Reference/S2U Map Icons.zip";

    @Before
    public void setUp() {
        h = new EngineHarness(tmp.getRoot());
    }

    @After
    public void noEngineCrashes() {
        assertTrue("engine threw: " + h.log.crashes, h.log.crashes.isEmpty());
    }

    private File installedFile(Snapshot.RecordView v) {
        return new File(v.record.localPath());
    }

    private Snapshot.RecordView installOne(RepoEntry e) {
        h.engine.install(e);
        h.advance(Tuning.CONFIRM_POLL_MS);
        return h.recordAt(e.path());
    }

    // ------------------------------------------------------------- installing

    @Test
    public void installedMapLayerIsLoadedInPlaceAndActive() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        Snapshot.RecordView v = installOne(e);

        assertNotNull(v);
        assertEquals(InstallRecord.State.ACTIVE, v.record.state());
        assertEquals(Snapshot.Presence.ACTIVE, v.presence);
        File f = installedFile(v);
        assertTrue(f.isFile());
        assertEquals(EngineHarness.canonical(h.installDir), EngineHarness.canonical(f.getParentFile()));
        assertEquals(1, h.platform.count("import"));
        assertEquals("[" + e.fileName() + "]", h.finished.toString());
        assertTrue(h.last().jobs.isEmpty());
        String[] leftovers = h.downloadDir.list();
        assertTrue(leftovers == null || leftovers.length == 0);
    }

    @Test
    public void installingTheSameFileTwiceDownloadsOnce() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        h.engine.install(e);
        h.engine.install(e); // double tap
        h.advance(Tuning.CONFIRM_POLL_MS);
        assertEquals(1, h.repo.downloads);
        assertEquals(1, h.last().records.size());
    }

    @Test
    public void sameFileNameFromAnotherFolderGetsItsOwnLocalName() {
        RepoEntry a = h.kmz("A/Yemen FLOT.kmz");
        RepoEntry b = h.kmz("B/Yemen FLOT.kmz", "other");
        h.publish(a, b);
        h.start();
        installOne(a);
        installOne(b);
        assertEquals(2, h.last().records.size());
        assertEquals("Yemen FLOT (2).kmz", installedFile(h.recordAt(b.path())).getName());
        assertTrue(installedFile(h.recordAt(a.path())).isFile());
    }

    // ---------------------------------------------------------------- updates

    @Test
    public void updateRemovesTheOldVersionOnlyAfterTheNewOneIsLoaded() {
        RepoEntry v1 = h.kmz(V1);
        h.publish(v1);
        h.start();
        Snapshot.RecordView old = installOne(v1);
        File oldFile = installedFile(old);

        RepoEntry v2 = h.kmz(V2);
        h.publish(v2); // v1 deleted from the repository, v2 published
        h.engine.refresh();
        h.settle();
        assertEquals(1, h.last().updateCount);

        h.platform.autoLoad = false; // ATAK takes its time
        h.engine.updateTo(h.last().catalog().find(V2));
        h.advance(5_000);
        assertTrue("old version must stay until the new one is in", oldFile.isFile());
        assertNotNull(h.recordAt(V1));
        assertEquals(Snapshot.JobPhase.WAITING, h.last().jobFor(V2).phase);

        Snapshot.RecordView fresh = h.recordAt(V2);
        h.platform.loaded.add(EngineHarness.canonical(installedFile(fresh)));
        h.advance(Tuning.CONFIRM_POLL_MS + Tuning.EVENT_DEBOUNCE_MS * 2);

        assertNull(h.recordAt(V1));
        assertFalse(oldFile.exists());
        assertEquals(InstallRecord.State.ACTIVE, h.recordAt(V2).record.state());
        assertTrue(h.recordAt(V2).record.replaces().isEmpty());
        assertEquals(0, h.last().updateCount);
    }

    @Test
    public void slowImportKeepsTheOldVersionAndFinishesLater() {
        RepoEntry v1 = h.kmz(V1);
        h.publish(v1);
        h.start();
        Snapshot.RecordView old = installOne(v1);
        RepoEntry v2 = h.kmz(V2);
        h.publish(v1, v2);
        h.engine.refresh();
        h.settle();
        h.platform.autoLoad = false;
        h.engine.updateTo(h.last().catalog().find(V2));
        h.advance(Tuning.confirmTimeoutMs(FileKind.MAP_LAYER, v2.size()) + 2_000);

        assertTrue(h.finished.toString(), h.finished.get(h.finished.size() - 1).endsWith("STILL-LOADING"));
        assertNotNull("old version kept while the new one isn't confirmed", h.recordAt(V1));
        assertTrue(installedFile(h.recordAt(V1)).isFile());
        assertEquals(InstallRecord.State.IMPORT_REQUESTED, h.recordAt(V2).record.state());

        // ATAK finishes loading later and says so
        h.platform.loaded.add(EngineHarness.canonical(installedFile(h.recordAt(V2))));
        h.engine.deviceChanged();
        h.advance(Tuning.EVENT_DEBOUNCE_MS * 3);
        assertEquals(InstallRecord.State.ACTIVE, h.recordAt(V2).record.state());
        assertNull(h.recordAt(V1));
    }

    @Test
    public void updateAllReplacesExactlyThePlannedRecords() {
        RepoEntry v1 = h.kmz(V1);
        RepoEntry other = h.kmz("Lebanon/Litani_River_August_1_2026.kmz");
        h.publish(v1, other);
        h.start();
        installOne(v1);
        installOne(other);
        RepoEntry v2 = h.kmz(V2);
        h.publish(v1, v2, other);
        h.engine.refresh();
        h.settle();
        h.engine.updateAll();
        h.advance(10_000);
        assertNull(h.recordAt(V1));
        assertNotNull(h.recordAt(V2));
        assertNotNull("unrelated product untouched", h.recordAt(other.path()));
        assertTrue(installedFile(h.recordAt(other.path())).isFile());
    }

    @Test
    public void fileMovedInTheRepositoryIsFollowedWithoutADownload() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        installOne(e);
        int downloads = h.repo.downloads;
        RepoEntry moved = h.entry("Middle East/Archive/" + e.fileName(), h.repo.content.get(e.sha()));
        RepoEntry movedLive = h.entry("Middle East/Iran/" + e.fileName(), h.repo.content.get(e.sha()));
        h.publish(moved, movedLive);
        h.engine.refresh();
        h.settle();
        Snapshot.RecordView v = h.only();
        assertEquals(movedLive.path(), v.record.repoPath());
        assertEquals(UpdatePlanner.Kind.UP_TO_DATE, v.status.kind);
        assertEquals(downloads, h.repo.downloads);
    }

    // ---------------------------------------------------------------- removal

    @Test
    public void removeUnloadsAndDeletesGhostMapsOwnFile() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        File f = installedFile(installOne(e));
        h.engine.remove(h.only().record.id());
        h.advance(Tuning.EVENT_DEBOUNCE_MS * 2);
        assertEquals(1, h.platform.count("remove"));
        assertFalse(f.exists());
        assertTrue(h.last().records.isEmpty());
    }

    @Test
    public void removeIsRetriedIfAtakDidNotUnload() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        File f = installedFile(installOne(e));
        h.platform.ignoreRemoves = 1; // ATAK drops the first request
        h.engine.remove(h.only().record.id());
        h.advance(Tuning.EVENT_DEBOUNCE_MS * 2);
        assertEquals(Snapshot.Presence.REMOVING, h.only().presence);
        h.advance(Tuning.REMOVE_GRACE_MS + Tuning.MISSING_GRACE_MS * 2);
        assertEquals(2, h.platform.count("remove"));
        assertTrue(h.last().records.isEmpty());
        assertFalse(f.exists());
    }

    @Test
    public void filesFromEarlyTestBuildsAreNeverDeleted() throws Exception {
        File overlays = new File(tmp.getRoot(), "atak/overlays");
        assertTrue(overlays.mkdirs());
        File legacyFile = new File(overlays, "Iran.kmz");
        Files.write(legacyFile.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        InstallStore seed = new InstallStore(h.storeFile, h.installDir, h.clock);
        InstallRecord legacy = new InstallRecord(InstallStore.newId(), V1, EngineHarness.blobSha(new byte[] { 1 }),
                1, FileKind.MAP_LAYER, legacyFile.getAbsolutePath(), null, InstallRecord.Origin.LEGACY,
                InstallRecord.State.ACTIVE, 1, 1, null);
        seed.put(legacy);
        h.platform.loaded.add(EngineHarness.canonical(legacyFile));
        h.publish(h.kmz(V2));
        h.start();
        assertEquals(Snapshot.Presence.UNMANAGED, h.only().presence);
        assertFalse(h.only().hasUpdate());

        h.engine.updateAll();
        h.engine.remove(legacy.id());
        h.advance(60_000);
        assertTrue(legacyFile.isFile());
        assertEquals(0, h.platform.count("remove"));
        assertTrue(h.platform.loaded.contains(EngineHarness.canonical(legacyFile)));
        assertEquals(0, h.repo.downloads);
    }

    @Test
    public void forgetDropsOnlyTheRecord() throws Exception {
        File outside = new File(tmp.getRoot(), "atak/overlays/Iran.kmz");
        outside.getParentFile().mkdirs();
        Files.write(outside.toPath(), "x".getBytes(StandardCharsets.UTF_8));
        InstallRecord legacy = new InstallRecord(InstallStore.newId(), V1, EngineHarness.blobSha(new byte[] { 1 }),
                1, FileKind.MAP_LAYER, outside.getAbsolutePath(), null, InstallRecord.Origin.LEGACY,
                InstallRecord.State.ACTIVE, 1, 1, null);
        new InstallStore(h.storeFile, h.installDir, h.clock).put(legacy);
        h.start();
        h.engine.forget(legacy.id());
        h.settle();
        assertTrue(h.last().records.isEmpty());
        assertTrue(outside.isFile());
    }

    // ------------------------------------------------- changes made outside GhostMaps

    @Test
    public void layerDeletedInOverlayManagerIsForgottenAfterTheGracePeriod() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        File f = installedFile(installOne(e));
        h.platform.removeMapLayer(f); // the user deletes it in ATAK
        h.engine.deviceChanged();
        h.advance(Tuning.EVENT_DEBOUNCE_MS);
        assertEquals("not forgotten on the first look", 1, h.last().records.size());
        h.advance(Tuning.MISSING_GRACE_MS + 1);
        assertTrue(h.last().records.isEmpty());
    }

    @Test
    public void nothingIsDecidedWhileStorageIsUnavailable() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        installOne(e);
        h.platform.loaded.clear();
        h.platform.storageAvailable = false;
        new File(h.only().record.localPath()).delete(); // looks missing while unmounted
        h.engine.deviceChanged();
        h.advance(5 * 60_000);
        assertEquals(1, h.last().records.size());
    }

    @Test
    public void nothingIsDecidedWhileAtakCannotBeRead() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        File f = installedFile(installOne(e));
        h.platform.unreadable = true;
        int calls = h.platform.calls.size();
        h.engine.panelOpened();
        h.engine.deviceChanged();
        h.advance(5 * 60_000);
        assertEquals(1, h.last().records.size());
        assertTrue(f.isFile());
        assertEquals(calls, h.platform.calls.size());
    }

    @Test
    public void lostLayerIsReloadedOnceOnlyAfterStartupAndPanelUse() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        File f = installedFile(installOne(e));
        int imports = h.platform.count("import");
        h.platform.autoLoad = false;
        h.platform.loaded.remove(EngineHarness.canonical(f)); // ATAK dropped it

        h.engine.deviceChanged();
        h.advance(Tuning.EVENT_DEBOUNCE_MS);
        assertEquals("never during start-up", imports, h.platform.count("import"));

        h.advance(Tuning.STARTUP_QUIET_MS);
        h.engine.deviceChanged();
        h.advance(Tuning.EVENT_DEBOUNCE_MS);
        assertEquals("not before the panel is used", imports, h.platform.count("import"));

        h.engine.panelOpened();
        h.advance(Tuning.EVENT_DEBOUNCE_MS);
        assertEquals(imports + 1, h.platform.count("import"));

        for (int i = 0; i < 5; i++) {
            h.engine.panelOpened();
            h.engine.deviceChanged();
            h.advance(Tuning.MISSING_GRACE_MS);
        }
        assertEquals("once per session", imports + 1, h.platform.count("import"));
        assertEquals(Snapshot.Presence.NOT_LOADED, h.only().presence);
        assertTrue(f.isFile());

        h.engine.reload(h.only().record.id()); // the user can always ask
        h.settle();
        assertEquals(imports + 2, h.platform.count("import"));
    }

    // --------------------------------------------------------------- icon sets

    @Test
    public void iconSetIsAddedThroughAtakIconManagerNotAsAMapLayer() throws Exception {
        RepoEntry icons = h.iconZip(ICONS, "S2U Map Icons/", "S2U Map Icons/S2 Icons/Drone.png",
                "S2U Map Icons/S2 Icons/UAS.png");
        h.publish(icons);
        h.start();
        Snapshot.RecordView v = installOne(icons);
        assertEquals(InstallRecord.State.ACTIVE, v.record.state());
        assertEquals(Snapshot.Presence.ACTIVE, v.presence);
        assertNotNull(v.record.iconSetUid());
        assertTrue(h.platform.iconSets.contains(v.record.iconSetUid()));
        assertEquals(0, h.platform.count("import"));
        assertEquals(1, h.platform.count("addIcons"));

        File zip = installedFile(v);
        h.engine.remove(v.record.id());
        h.advance(Tuning.EVENT_DEBOUNCE_MS * 2);
        assertFalse(h.platform.iconSets.contains(v.record.iconSetUid()));
        assertFalse(zip.exists());
        assertTrue(h.last().records.isEmpty());
    }

    @Test
    public void iconSetFromAnotherSourceIsLeftAlone() throws Exception {
        RepoEntry icons = h.iconZip(ICONS, "a.png");
        h.publish(icons);
        h.start();
        File probe = tmp.newFile("same.zip");
        Files.write(probe.toPath(), h.repo.content.get(icons.sha()));
        String uid = h.platform.iconSetUid(probe, null);
        h.platform.iconSets.add(uid); // e.g. DataSync or a manual import

        h.engine.install(icons);
        h.advance(Tuning.CONFIRM_POLL_MS);
        assertTrue(h.finished.toString(), h.finished.get(0).contains("another source"));
        assertTrue(h.last().records.isEmpty());
        assertTrue(h.platform.iconSets.contains(uid));
        assertEquals(0, h.platform.count("addIcons"));
        String[] left = h.installDir.list();
        assertTrue(left == null || left.length == 0);
    }

    @Test
    public void zipThatIsNotAnIconSetIsRefused() throws Exception {
        RepoEntry pkg = h.iconZip("Packages/Mission.zip", "MANIFEST/manifest.xml", "layer.kmz");
        h.publish(pkg);
        h.start();
        h.engine.install(pkg);
        h.advance(Tuning.CONFIRM_POLL_MS);
        assertTrue(h.finished.toString(), h.finished.get(0).contains("Not an icon set"));
        assertEquals(Snapshot.JobPhase.FAILED, h.last().jobFor(pkg.path()).phase);
        assertTrue(h.last().records.isEmpty());
        assertEquals(0, h.platform.count("addIcons"));
    }

    @Test
    public void iconSetDeletedInAtakIsForgottenAfterTheGracePeriod() throws Exception {
        RepoEntry icons = h.iconZip(ICONS, "a.png");
        h.publish(icons);
        h.start();
        Snapshot.RecordView v = installOne(icons);
        File zip = installedFile(v);
        h.platform.iconSets.clear();
        h.engine.deviceChanged();
        h.advance(Tuning.EVENT_DEBOUNCE_MS);
        assertTrue("a brief gap doesn't delete anything", zip.isFile());
        h.advance(Tuning.MISSING_GRACE_MS + 1);
        assertFalse(zip.exists());
        assertTrue(h.last().records.isEmpty());
    }

    @Test
    public void atakIgnoringTheDeclaredUidIsLearned() throws Exception {
        // ATAK falls back to the ZIP's own hash when it rejects iconset.xml
        File zip = com.atakmap.android.ghostmaps.plugin.core.Fixtures.zipWithXml(tmp.newFile("x.zip"),
                "<iconset uid=\"declared\"/>", "a.png");
        RepoEntry icons = h.entry(ICONS, Files.readAllBytes(zip.toPath()));
        h.publish(icons);
        h.start();
        h.platform.autoAddIcons = false;
        h.engine.install(icons);
        h.settle();
        String atakUid = h.platform.iconSetUid(zip, null);
        h.platform.iconSets.add(atakUid);
        h.engine.iconSetAdded(atakUid);
        h.advance(Tuning.EVENT_DEBOUNCE_MS * 2);
        Snapshot.RecordView v = h.only();
        assertEquals(atakUid, v.record.iconSetUid());
        assertEquals(InstallRecord.State.ACTIVE, v.record.state());
    }

    // ------------------------------------------------------------- recovery

    @Test
    public void crashAfterPlacingTheFileIsFinishedOnNextStart() throws Exception {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        assertTrue(h.installDir.mkdirs());
        File placed = new File(h.installDir, e.fileName());
        Files.write(placed.toPath(), h.repo.content.get(e.sha()));
        new InstallStore(h.storeFile, h.installDir, h.clock).put(new InstallRecord(InstallStore.newId(),
                e.path(), e.sha(), e.size(), e.kind(), placed.getAbsolutePath(), null,
                InstallRecord.Origin.GHOSTMAPS, InstallRecord.State.PLACED, 1, 1, null));
        h.start();
        h.advance(Tuning.MISSING_GRACE_MS);
        assertEquals(InstallRecord.State.ACTIVE, h.only().record.state());
        assertEquals(1, h.platform.count("import"));
        assertEquals(0, h.repo.downloads);
    }

    @Test
    public void lostInstallListIsRebuiltFromGhostMapsFolder() throws Exception {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        assertTrue(h.installDir.mkdirs());
        File f = new File(h.installDir, "renamed by user.kmz");
        Files.write(f.toPath(), h.repo.content.get(e.sha()));
        File stranger = new File(h.installDir, "my own notes.kmz");
        Files.write(stranger.toPath(), "mine".getBytes(StandardCharsets.UTF_8));
        h.platform.loaded.add(EngineHarness.canonical(f));
        h.storeFile.getParentFile().mkdirs();
        Files.write(h.storeFile.toPath(), "{corrupt".getBytes(StandardCharsets.UTF_8));

        h.start();
        Snapshot.RecordView v = h.only();
        assertEquals(e.path(), v.record.repoPath());
        assertEquals(Snapshot.Presence.ACTIVE, v.presence);
        assertTrue("files GhostMaps can't identify are left alone", stranger.isFile());
    }

    // ------------------------------------------------------------- downloads

    @Test
    public void failedDownloadShowsTheErrorAndCanBeRetried() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        h.repo.failNextDownloads = 1;
        h.engine.install(e);
        h.settle();
        Snapshot.JobView j = h.last().jobFor(V1);
        assertEquals(Snapshot.JobPhase.FAILED, j.phase);
        assertNotNull(j.error);
        assertTrue(h.last().records.isEmpty());

        h.engine.install(e); // Retry
        h.advance(Tuning.CONFIRM_POLL_MS);
        assertNull(h.last().jobFor(V1));
        assertEquals(InstallRecord.State.ACTIVE, h.only().record.state());
    }

    @Test
    public void failedUpdateRetryStillReplacesTheOldVersion() {
        RepoEntry v1 = h.kmz(V1);
        h.publish(v1);
        h.start();
        Snapshot.RecordView old = installOne(v1);
        RepoEntry v2 = h.kmz(V2);
        h.publish(v1, v2);
        h.engine.refresh();
        h.settle();
        h.repo.failNextDownloads = 1;
        h.engine.updateTo(h.last().catalog().find(V2));
        h.settle();
        assertEquals(Snapshot.JobPhase.FAILED, h.last().jobFor(V2).phase);
        h.engine.install(v2); // the Retry button
        h.advance(Tuning.CONFIRM_POLL_MS + Tuning.EVENT_DEBOUNCE_MS * 2);
        assertNull("the retried update still replaces v1", h.recordAt(V1));
    }

    @Test
    public void cancelDuringDownloadLeavesNothingBehind() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        h.engine.install(e);
        h.state.runDue(); // job starts; download is queued on the network thread
        assertEquals(Snapshot.JobPhase.DOWNLOADING, h.last().jobFor(V1).phase);
        h.engine.cancel(V1);
        h.state.runDue();
        h.settle();
        assertNull(h.last().jobFor(V1));
        assertTrue(h.last().records.isEmpty());
        assertTrue(h.finished.isEmpty());
    }

    @Test
    public void disposeDuringADownloadWritesNothingAfterwards() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        h.engine.install(e);
        h.state.runDue();
        List<Runnable> inFlight = h.net.drain();
        int snaps = h.snapshots.size();
        h.engine.dispose();
        for (Runnable r : inFlight)
            r.run(); // the download thread finishes after dispose
        h.state.drain(); // nothing may run (the executor is shut down)
        assertEquals(snaps, h.snapshots.size());
        assertTrue(h.finished.isEmpty());
        assertEquals(0, new InstallStore(h.storeFile, h.installDir, h.clock).size());
    }

    @Test
    public void firstRunOfflineExplainsItself() {
        h.start();
        h.engine.panelOpened();
        h.settle();
        assertNull(h.last().listing);
        assertEquals("offline", h.last().listingError);
        assertFalse(h.last().refreshing);
    }

    // ------------------------------------------- regressions from the independent review

    @Test
    public void removingTheNewVersionMidUpdateKeepsTheOldOne() {
        RepoEntry v1 = h.kmz(V1);
        h.publish(v1);
        h.start();
        Snapshot.RecordView old = installOne(v1);
        RepoEntry v2 = h.kmz(V2);
        h.publish(v1, v2);
        h.engine.refresh();
        h.settle();
        h.platform.autoLoad = false;
        h.engine.updateTo(v2);
        h.advance(2_000);
        Snapshot.RecordView fresh = h.recordAt(V2);
        File freshFile = installedFile(fresh);

        h.engine.remove(fresh.record.id()); // refused while its own install runs
        h.settle();
        assertTrue(freshFile.isFile());
        assertEquals(InstallRecord.State.IMPORT_REQUESTED, h.recordAt(V2).record.state());

        h.advance(Tuning.confirmTimeoutMs(FileKind.MAP_LAYER, v2.size())); // "still loading"
        h.engine.remove(fresh.record.id()); // now allowed
        h.settle();
        h.platform.loaded.add(EngineHarness.canonical(freshFile)); // ATAK loads it anyway
        h.engine.deviceChanged();
        h.advance(Tuning.REMOVE_GRACE_MS + Tuning.MISSING_GRACE_MS * 2);

        assertNull(h.recordAt(V2));
        assertNotNull("the old version must survive", h.recordAt(V1));
        assertTrue(installedFile(old).isFile());
    }

    @Test
    public void iconSetNewVersionWithTheSameUidReplacesOnlyOnceItIsIn() throws Exception {
        RepoEntry a = h.entry(ICONS, java.nio.file.Files.readAllBytes(com.atakmap.android.ghostmaps.plugin.core.Fixtures
                .zipWithXml(tmp.newFile("a.zip"), "<iconset uid=\"s2u\"/>", "a.png").toPath()));
        h.publish(a);
        h.start();
        File oldZip = installedFile(installOne(a));
        RepoEntry b = h.entry(ICONS, java.nio.file.Files.readAllBytes(com.atakmap.android.ghostmaps.plugin.core.Fixtures
                .zipWithXml(tmp.newFile("b.zip"), "<iconset uid=\"s2u\"/>", "a.png", "b.png").toPath()));
        h.publish(b);
        h.engine.refresh();
        h.settle();
        assertEquals(1, h.last().updateCount);
        h.engine.updateTo(b);
        h.advance(Tuning.CONFIRM_POLL_MS * 3);
        Snapshot.RecordView v = h.only();
        assertEquals(b.sha(), v.record.sha());
        assertEquals(InstallRecord.State.ACTIVE, v.record.state());
        assertTrue("the shared UID must not be removed from ATAK", h.platform.iconSets.contains("s2u"));
        assertEquals(0, h.platform.count("removeIcons"));
        assertFalse(oldZip.exists());
    }

    @Test
    public void differentProductWithTheSameIconSetUidIsRefused() throws Exception {
        RepoEntry a = h.entry(ICONS, java.nio.file.Files.readAllBytes(com.atakmap.android.ghostmaps.plugin.core.Fixtures
                .zipWithXml(tmp.newFile("a.zip"), "<iconset uid=\"s2u\"/>", "a.png").toPath()));
        RepoEntry other = h.entry("Other/Other Icons.zip", java.nio.file.Files.readAllBytes(
                com.atakmap.android.ghostmaps.plugin.core.Fixtures.zipWithXml(tmp.newFile("o.zip"),
                        "<iconset uid=\"s2u\"/>", "z.png").toPath()));
        h.publish(a, other);
        h.start();
        installOne(a);
        h.engine.install(other);
        h.advance(Tuning.CONFIRM_POLL_MS);
        assertTrue(h.finished.toString(), h.finished.get(1).contains("same ID"));
        assertEquals(a.sha(), h.only().record.sha());
        assertTrue(h.platform.iconSets.contains("s2u"));
    }

    @Test
    public void someoneElsesIconSetAppearingMidInstallIsNotClaimed() throws Exception {
        RepoEntry icons = h.iconZip(ICONS, "a.png");
        h.publish(icons);
        h.start();
        h.platform.autoAddIcons = false;
        h.engine.install(icons);
        h.settle();
        h.platform.iconSets.add("users-own-set");
        h.engine.iconSetAdded("users-own-set"); // e.g. the user imported a set meanwhile
        h.advance(Tuning.EVENT_DEBOUNCE_MS * 2);
        assertNotEquals("users-own-set", h.only().record.iconSetUid());
        h.engine.remove(h.only().record.id());
        h.advance(Tuning.REMOVE_GRACE_MS * 2);
        assertTrue(h.platform.iconSets.contains("users-own-set"));
    }

    @Test
    public void iconSetIsNotInstalledWhileAtakCannotBeRead() throws Exception {
        RepoEntry icons = h.iconZip(ICONS, "a.png");
        h.publish(icons);
        h.start();
        h.platform.unreadable = true;
        h.engine.install(icons);
        h.settle();
        assertTrue(h.finished.toString(), h.finished.get(0).contains("Couldn't read ATAK"));
        assertEquals(0, h.platform.count("addIcons"));
    }

    @Test
    public void retryAfterTheFileChangedOnGitHubDownloadsTheCurrentVersion() {
        RepoEntry first = h.kmz("Ops/Kinetic_Activities_Last_30_Days.kmz", "monday");
        h.publish(first);
        h.start();
        h.repo.failNextDownloads = 1;
        h.engine.install(first);
        h.settle();
        assertEquals(Snapshot.JobPhase.FAILED, h.last().jobFor(first.path()).phase);
        RepoEntry today = h.kmz(first.path(), "tuesday"); // same path, new contents
        h.publish(today);
        h.engine.refresh();
        h.settle();
        h.engine.install(h.last().jobFor(first.path()).target); // Retry passes the old entry
        h.advance(Tuning.CONFIRM_POLL_MS);
        assertEquals(today.sha(), h.only().record.sha());
    }

    @Test
    public void installFromAStaleListOfADeletedFileExplainsItself() {
        RepoEntry e = h.kmz(V1);
        h.publish(e);
        h.start();
        h.publish(h.kmz(V2));
        h.engine.refresh();
        h.settle();
        h.engine.install(e);
        h.settle();
        assertTrue(h.finished.toString(), h.finished.get(0).contains("No longer on GitHub"));
        assertEquals(0, h.repo.downloads);
    }

    @Test
    public void replacementsAddedWhileAnInstallRunsAreHonored() {
        RepoEntry v1 = h.kmz(V1);
        h.publish(v1);
        h.start();
        installOne(v1);
        RepoEntry v2 = h.kmz(V2);
        h.publish(v1, v2);
        h.engine.refresh();
        h.settle();
        h.platform.autoLoad = false;
        h.engine.install(v2); // a plain install: replaces nothing
        h.advance(2_000);
        h.engine.updateTo(v2); // then Update on the old row while it's still loading
        h.settle();
        h.platform.loaded.add(EngineHarness.canonical(installedFile(h.recordAt(V2))));
        h.advance(Tuning.CONFIRM_POLL_MS + Tuning.EVENT_DEBOUNCE_MS * 2);
        assertNull(h.recordAt(V1));
    }

    @Test
    public void somethingThatIsNotAKmzIsNeverHandedToAtak() {
        RepoEntry lfs = h.entry("Big/Census.kmz",
                "version https://git-lfs.github.com/spec/v1\noid sha256:abc\nsize 62000000\n"
                        .getBytes(StandardCharsets.UTF_8));
        h.publish(lfs);
        h.start();
        h.engine.install(lfs);
        h.settle();
        assertTrue(h.finished.toString(), h.finished.get(0).contains("Git LFS"));
        assertEquals(0, h.platform.count("import"));
        String[] left = h.installDir.list();
        assertTrue(left == null || left.length == 0);
    }
}
