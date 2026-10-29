package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

public class InstallStoreTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File own;
    private File storeFile;
    private final Clock clock = new Fixtures.FakeClock(1_000_000);

    @Before
    public void setUp() throws Exception {
        own = tmp.newFolder("GhostMaps");
        storeFile = new File(tmp.getRoot(), "data/installed.json");
    }

    private InstallRecord rec(String path, String state) {
        return new InstallRecord(InstallStore.newId(), path, Fixtures.sha(path), 10,
                FileKind.fromFileName(path), new File(own, new File(path).getName()).getAbsolutePath(),
                null, InstallRecord.Origin.GHOSTMAPS, InstallRecord.State.valueOf(state), 5, 5,
                Arrays.asList("a", "b"));
    }

    @Test
    public void roundTripKeepsEveryField() throws Exception {
        InstallStore s = new InstallStore(storeFile, own, clock);
        InstallRecord r = rec("A/x.kmz", "IMPORT_REQUESTED").withIconSetUid("uid-1");
        s.put(r);
        InstallStore again = new InstallStore(storeFile, own, clock);
        InstallRecord back = again.get(r.id());
        assertNotNull(back);
        assertEquals(r.repoPath(), back.repoPath());
        assertEquals(r.sha(), back.sha());
        assertEquals(InstallRecord.State.IMPORT_REQUESTED, back.state());
        assertEquals("uid-1", back.iconSetUid());
        assertEquals(Arrays.asList("a", "b"), back.replaces());
        assertFalse(again.recoveredFromCorruption());
    }

    @Test
    public void corruptFileIsQuarantinedNotOverwritten() throws Exception {
        storeFile.getParentFile().mkdirs();
        Files.write(storeFile.toPath(), "{not json".getBytes(StandardCharsets.UTF_8));
        InstallStore s = new InstallStore(storeFile, own, clock);
        assertTrue(s.recoveredFromCorruption());
        assertEquals(0, s.size());
        File[] q = storeFile.getParentFile().listFiles((d, n) -> n.startsWith("installed.json.corrupt-"));
        assertEquals(1, q.length);
        assertEquals("{not json", new String(Files.readAllBytes(q[0].toPath()), StandardCharsets.UTF_8));
    }

    @Test
    public void corruptMainFileFallsBackToTheBackup() throws Exception {
        InstallStore s = new InstallStore(storeFile, own, clock);
        InstallRecord r = rec("A/x.kmz", "ACTIVE");
        s.put(r);
        // simulate a crash that left a good backup and a damaged main file
        Files.copy(storeFile.toPath(), new File(storeFile.getPath() + ".bak").toPath());
        Files.write(storeFile.toPath(), "garbage".getBytes(StandardCharsets.UTF_8));
        InstallStore again = new InstallStore(storeFile, own, clock);
        assertNotNull(again.get(r.id()));
        assertTrue(again.recoveredFromCorruption());
    }

    @Test
    public void missingMainFileReadsTheBackup() throws Exception {
        InstallStore s = new InstallStore(storeFile, own, clock);
        InstallRecord r = rec("A/x.kmz", "ACTIVE");
        s.put(r);
        File bak = new File(storeFile.getPath() + ".bak");
        assertTrue(storeFile.renameTo(bak)); // crash between "main → bak" and "tmp → main"
        assertNotNull(new InstallStore(storeFile, own, clock).get(r.id()));
    }

    @Test
    public void version1IsMigratedAndOutsideFilesBecomeLegacy() throws Exception {
        storeFile.getParentFile().mkdirs();
        String inside = new File(own, "Litani_River.kmz").getAbsolutePath();
        String outside = new File(tmp.getRoot(), "atak/overlays/Iran.kmz").getAbsolutePath();
        String v1 = "{\"version\":1,\"installed\":["
                + "{\"repoPath\":\"M/Litani_River.kmz\",\"sha\":\"" + Fixtures.sha("l") + "\",\"size\":5,"
                + "\"localPath\":\"" + inside + "\",\"contentType\":\"KML\",\"installedAt\":7},"
                + "{\"repoPath\":\"I/Iran.kmz\",\"sha\":\"" + Fixtures.sha("i") + "\",\"size\":5,"
                + "\"localPath\":\"" + outside + "\",\"contentType\":\"KML\",\"installedAt\":8},"
                + "{\"repoPath\":\"R/S2U Map Icons.zip\",\"sha\":\"" + Fixtures.sha("z") + "\",\"size\":5,"
                + "\"localPath\":\"" + new File(own, "S2U Map Icons.zip").getAbsolutePath() + "\"}]}";
        Files.write(storeFile.toPath(), v1.getBytes(StandardCharsets.UTF_8));
        InstallStore s = new InstallStore(storeFile, own, clock);
        assertEquals(3, s.size());
        assertEquals(InstallRecord.Origin.GHOSTMAPS, s.findByRepoPath("M/Litani_River.kmz").origin());
        assertEquals(InstallRecord.Origin.LEGACY, s.findByRepoPath("I/Iran.kmz").origin());
        InstallRecord icons = s.findByRepoPath("R/S2U Map Icons.zip");
        assertEquals(FileKind.ICON_SET, icons.kind());
        assertNull(icons.iconSetUid()); // learned later from the file
        // saved as version 2
        assertTrue(new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8)
                .contains("\"version\": 2"));
    }

    @Test
    public void insideCheckRejectsTraversal() throws Exception {
        assertTrue(InstallStore.isInside(new File(own, "a.kmz"), own));
        assertFalse(InstallStore.isInside(new File(own, "../a.kmz"), own));
        assertFalse(InstallStore.isInside(own, own));
        assertFalse(InstallStore.isInside(new File(own.getPath() + "Evil/a.kmz"), own));
    }

    @Test
    public void failedWriteLeavesMemoryAndDiskConsistent() throws Exception {
        InstallStore s = new InstallStore(storeFile, own, clock);
        InstallRecord r = rec("A/x.kmz", "ACTIVE");
        s.put(r);
        // make the next write fail: a directory where the temp file goes
        assertTrue(new File(storeFile.getPath() + ".tmp").mkdirs());
        try {
            s.put(rec("B/y.kmz", "ACTIVE"));
        } catch (java.io.IOException expected) {
            // expected
        }
        assertEquals(1, s.size());
        assertEquals(1, new InstallStore(storeFile, own, clock).size());
    }

    @Test
    public void damagedRecordIsFlaggedNotSilentlyDropped() throws Exception {
        InstallStore s = new InstallStore(storeFile, own, clock);
        InstallRecord good = rec("A/x.kmz", "ACTIVE");
        s.put(good);
        String text = new String(Files.readAllBytes(storeFile.toPath()), StandardCharsets.UTF_8);
        String damaged = text.replace("\"installed\": [", "\"installed\": [{\"id\": \"broken\"},");
        assertTrue(damaged.contains("broken"));
        Files.write(storeFile.toPath(), damaged.getBytes(StandardCharsets.UTF_8));
        InstallStore again = new InstallStore(storeFile, own, clock);
        assertNotNull(again.get(good.id()));
        assertEquals(1, again.size());
        assertTrue(again.recoveredFromCorruption());
    }
}
