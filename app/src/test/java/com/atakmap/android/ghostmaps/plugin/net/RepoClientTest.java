package com.atakmap.android.ghostmaps.plugin.net;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.atakmap.android.ghostmaps.plugin.core.Clock;
import com.atakmap.android.ghostmaps.plugin.core.RepoEntry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/** The GitHub client against a local fake server. */
public class RepoClientTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private FakeGitHub gh;
    private File data;
    private static final String PATH = "Incident Response (KMZs)/Coeur d'Alene, Idaho - June 29, 2025/Map v1.kmz";

    @Before
    public void setUp() throws Exception {
        gh = new FakeGitHub();
        gh.files.put(PATH, FakeGitHub.bytes(300_000, 7));
        gh.files.put("Ref/S2U Map Icons.zip", FakeGitHub.bytes(1000, 3));
        gh.files.put("README.md", FakeGitHub.bytes(10, 1));
        data = tmp.newFolder("data");
    }

    @After
    public void tearDown() {
        gh.close();
    }

    private RepoClient client() {
        return new RepoClient(gh.apiBase(), gh.rawBase(), "o", "r", "main", data, Clock.SYSTEM,
                new RepoClient.Sleeper() {
                    @Override
                    public void sleep(long ms) {
                        // no waiting in tests
                    }
                });
    }

    @Test
    public void listingIsCommitPinnedAndCached() throws Exception {
        RepoClient c = client();
        RepoSource.Listing l = c.refresh();
        assertNull(l.notice);
        assertEquals(2, l.catalog.size()); // README skipped
        assertEquals(gh.commit, l.catalog.commitSha());
        assertNotNull(c.cached());
        assertEquals(gh.commit, c.cached().catalog.commitSha());
    }

    @Test
    public void unchangedRepoCostsOneConditionalRequest() throws Exception {
        RepoClient c = client();
        c.refresh();
        int trees = gh.treeCalls.get();
        int calls = gh.apiCalls.get();
        RepoSource.Listing again = c.refresh();
        assertNull(again.notice);
        assertEquals(trees, gh.treeCalls.get());
        assertEquals(calls + 1, gh.apiCalls.get());
    }

    @Test
    public void newCommitFetchesTheTreeAgain() throws Exception {
        RepoClient c = client();
        c.refresh();
        gh.files.put("New/Thing_October_29_2026.kmz", FakeGitHub.bytes(10, 2));
        gh.commit = "2222222222222222222222222222222222222222";
        RepoSource.Listing l = c.refresh();
        assertEquals(3, l.catalog.size());
        assertEquals(gh.commit, l.catalog.commitSha());
    }

    @Test
    public void rateLimitFallsBackToTheSavedListWithAReason() throws Exception {
        RepoClient c = client();
        c.refresh();
        gh.rateLimited = true;
        RepoSource.Listing l = c.refresh();
        assertNotNull(l.notice);
        assertTrue(l.notice, l.notice.contains("hourly limit"));
        assertEquals(2, l.catalog.size());
    }

    @Test
    public void nothingSavedAndOfflineGivesAClearError() throws Exception {
        gh.close();
        try {
            client().refresh();
            fail();
        } catch (RepoSource.NoListingException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("Connect to the internet"));
        }
    }

    @Test
    public void renamedDefaultBranchIsFollowed() throws Exception {
        gh.branch = "trunk";
        gh.defaultBranch = "trunk";
        RepoSource.Listing l = client().refresh();
        assertNull(l.notice);
        assertEquals(2, l.catalog.size());
    }

    @Test
    public void missingBranchWithNoAlternativeKeepsTheSavedList() throws Exception {
        RepoClient c = client();
        c.refresh();
        gh.branch = "gone"; // main disappears, default still "main"
        RepoSource.Listing l = c.refresh();
        assertNotNull(l.notice);
        gh.branch = "main"; // transient: the next check works on main again
        assertNull(c.refresh().notice);
    }

    @Test
    public void downloadVerifiesAndHandlesAwkwardPaths() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        File dest = new File(tmp.getRoot(), "out.kmz");
        c.download(e, gh.commit, dest, null, new CancelToken());
        assertArrayEquals(gh.files.get(PATH), Files.readAllBytes(dest.toPath()));
    }

    @Test
    public void droppedConnectionResumesWithARange() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        gh.rawCutAfter = 100_000;
        File dest = new File(tmp.getRoot(), "out.kmz");
        c.download(e, gh.commit, dest, null, new CancelToken());
        assertArrayEquals(gh.files.get(PATH), Files.readAllBytes(dest.toPath()));
        assertTrue(gh.rangeHeaders.toString(), gh.rangeHeaders.contains("bytes=100000-"));
    }

    @Test
    public void transientServerErrorsAreRetried() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        gh.raw500s = 2;
        File dest = new File(tmp.getRoot(), "out.kmz");
        c.download(e, gh.commit, dest, null, new CancelToken());
        assertTrue(dest.isFile());
    }

    @Test
    public void persistentServerErrorsGiveUpWithRetryAdvice() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        gh.raw500s = 10;
        try {
            c.download(e, gh.commit, new File(tmp.getRoot(), "out.kmz"), null, new CancelToken());
            fail();
        } catch (IOException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("Retry"));
        }
    }

    @Test
    public void serverIgnoringRangeRestartsCleanly() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        File part = new File(c.downloadDir(), e.sha() + ".part");
        part.getParentFile().mkdirs();
        Files.write(part.toPath(), java.util.Arrays.copyOf(gh.files.get(PATH), 50_000));
        gh.ignoreRange = true;
        File dest = new File(tmp.getRoot(), "out.kmz");
        c.download(e, gh.commit, dest, null, new CancelToken());
        assertArrayEquals(gh.files.get(PATH), Files.readAllBytes(dest.toPath()));
    }

    @Test
    public void wrongContentRangeNeverSplicesFiles() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        File part = new File(c.downloadDir(), e.sha() + ".part");
        part.getParentFile().mkdirs();
        Files.write(part.toPath(), java.util.Arrays.copyOf(gh.files.get(PATH), 50_000));
        gh.wrongContentRange = true;
        File dest = new File(tmp.getRoot(), "out.kmz");
        try {
            c.download(e, gh.commit, dest, null, new CancelToken());
        } catch (IOException ignored) {
            // acceptable: every attempt was refused
        }
        if (dest.exists())
            assertArrayEquals(gh.files.get(PATH), Files.readAllBytes(dest.toPath()));
    }

    @Test
    public void corruptBytesAreRejectedAndDiscarded() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        byte[] bad = gh.files.get(PATH).clone();
        bad[5] ^= 0x55;
        gh.corruptBody = bad;
        File dest = new File(tmp.getRoot(), "out.kmz");
        try {
            c.download(e, gh.commit, dest, null, new CancelToken());
            fail();
        } catch (IOException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("didn't match"));
        }
        assertFalse(dest.exists());
        assertFalse(new File(c.downloadDir(), e.sha() + ".part").exists());
        c.download(e, gh.commit, dest, null, new CancelToken()); // clean retry works
        assertArrayEquals(gh.files.get(PATH), Files.readAllBytes(dest.toPath()));
    }

    @Test
    public void cancelStopsAndKeepsThePartialForResume() throws Exception {
        RepoClient c = client();
        final RepoEntry e = c.refresh().catalog.find(PATH);
        final CancelToken t = new CancelToken();
        File dest = new File(tmp.getRoot(), "out.kmz");
        try {
            c.download(e, gh.commit, dest, new RepoSource.Progress() {
                @Override
                public void onProgress(long done, long total) {
                    t.cancel();
                }
            }, t);
        } catch (RepoSource.CancelledException expected) {
            // expected
        }
        assertFalse(dest.exists());
        c.download(e, gh.commit, dest, null, new CancelToken());
        assertArrayEquals(gh.files.get(PATH), Files.readAllBytes(dest.toPath()));
    }

    @Test
    public void fileGoneAtThatCommitIsAClearError() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        gh.files.remove(PATH);
        try {
            c.download(e, gh.commit, new File(tmp.getRoot(), "out.kmz"), null, new CancelToken());
            fail();
        } catch (IOException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("refresh"));
        }
    }

    @Test
    public void listingSavedByAnOlderBuildIsIgnored() throws Exception {
        Files.write(new File(data, "tree.json").toPath(), "{\"tree\":[]}".getBytes("UTF-8"));
        assertNull(client().cached()); // no commit recorded: fetch afresh
    }

    @Test
    public void pathEncoding() {
        assertEquals("A%20B/Coeur%20d%27Alene%2C%20Idaho/x%20%281%29.kmz",
                RepoClient.encodePath("A B/Coeur d'Alene, Idaho/x (1).kmz"));
    }

    @Test
    public void returnsToMainWhenItExistsAgain() throws Exception {
        gh.branch = "trunk";
        gh.defaultBranch = "trunk";
        RepoClient c = client();
        c.refresh(); // now on "trunk"
        gh.branch = "main";
        gh.defaultBranch = "main";
        gh.commit = "3333333333333333333333333333333333333333";
        RepoSource.Listing l = c.refresh();
        assertNull(l.notice);
        assertEquals(gh.commit, l.catalog.commitSha());
    }

    @Test
    public void redirectAwayFromGitHubIsRefused() throws Exception {
        RepoClient c = client();
        RepoEntry e = c.refresh().catalog.find(PATH);
        gh.redirectRawElsewhere = true; // "localhost" is not the configured raw host
        File dest = new File(tmp.getRoot(), "out.kmz");
        try {
            c.download(e, gh.commit, dest, null, new CancelToken());
            fail();
        } catch (IOException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().contains("outside GitHub"));
        }
        assertFalse(dest.exists());
    }
}
