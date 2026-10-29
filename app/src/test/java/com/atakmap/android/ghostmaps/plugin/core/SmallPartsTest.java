package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;

/** Icon-set validation, names, hashing, formatting, other sources. */
public class SmallPartsTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void imageOnlyZipLikeS2uMapIconsIsAnIconSet() throws Exception {
        File z = Fixtures.zip(tmp.newFile("icons.zip"), Arrays.asList(
                "S2U Map Icons/", "S2U Map Icons/S2 Icons/", "S2U Map Icons/S2 Icons/Drone Strike.png",
                "S2U Map Icons/S2 Icons/Friendly UAS.png", "__MACOSX/._x.png", ".DS_Store"));
        IconSetInspector.Result r = IconSetInspector.inspect(z);
        assertTrue(r.reason, r.valid);
        assertEquals(2, r.iconCount);
        assertNull(r.declaredUid);
    }

    @Test
    public void iconsetXmlUidIsRead() throws Exception {
        File z = Fixtures.zipWithXml(tmp.newFile("set.zip"),
                "<?xml version='1.0'?><iconset version=\"1\" name=\"S2\" uid=\"abc-123\"/>", "a.png");
        IconSetInspector.Result r = IconSetInspector.inspect(z);
        assertTrue(r.valid);
        assertEquals("abc-123", r.declaredUid);
    }

    @Test
    public void dataPackagesAndEmptyZipsAreNotIconSets() throws Exception {
        File pkg = Fixtures.zip(tmp.newFile("pkg.zip"), Arrays.asList(
                "MANIFEST/manifest.xml", "layer.kmz", "icon.png"));
        IconSetInspector.Result r = IconSetInspector.inspect(pkg);
        assertFalse(r.valid);
        assertTrue(r.reason, r.reason.contains("manifest.xml"));
        assertFalse(IconSetInspector.inspect(Fixtures.zip(tmp.newFile("e.zip"),
                Collections.singletonList("folder/"))).valid);
        File notZip = tmp.newFile("x.zip");
        Files.write(notZip.toPath(), "nope".getBytes(StandardCharsets.UTF_8));
        assertFalse(IconSetInspector.inspect(notZip).valid);
    }

    @Test
    public void uniqueLocalNames() {
        HashSet<String> taken = new HashSet<>(Arrays.asList("yemen flot.kmz", "yemen flot (2).kmz"));
        assertEquals("Yemen FLOT (3).kmz", LocalNames.unique("Yemen FLOT.kmz", taken));
        assertEquals("Other.kmz", LocalNames.unique("Other.kmz", taken));
        assertEquals("a_b.kmz", LocalNames.unique("a:b.kmz", Collections.<String>emptySet()));
        assertEquals("file.kmz", LocalNames.unique(".kmz", Collections.<String>emptySet()));
    }

    @Test
    public void gitBlobHashMatchesGit() throws Exception {
        // `printf 'hello\n' | git hash-object --stdin`
        File f = tmp.newFile("hello.txt");
        Files.write(f.toPath(), "hello\n".getBytes(StandardCharsets.US_ASCII));
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitBlobHasher.of(f));
        GitBlobHasher shortRead = new GitBlobHasher(6);
        shortRead.update("hell".getBytes(StandardCharsets.US_ASCII), 0, 4);
        assertFalse(shortRead.matches("ce013625030ba8dba906f756967f9e9ca394464a"));
    }

    @Test
    public void formats() {
        assertEquals("512 B", Formats.size(512));
        assertEquals("39 KB", Formats.size(39925));
        assertEquals("3.4 MB", Formats.size(3_565_000));
        assertEquals("62 MB", Formats.size(65_000_000));
        assertEquals("?", Formats.size(-1));
        assertEquals("Oct 27, 2026", Formats.date(20261027));
        assertEquals("Jul 2025", Formats.date(20250700));
        assertEquals("", Formats.date(0));
        assertEquals("ArcGIS Data for ATAK › Master Database",
                Formats.folder("ArcGIS Data for ATAK (KMZs)/Master Database"));
        assertEquals("Master Database", Formats.folderName("A (x)/Master Database"));
        assertEquals("Oct 10, 2026 · v2",
                Formats.version(FileName.parse("Iran_Kinetic_Events_October_10_2026_v2.kmz")));
    }

    @Test
    public void otherSourcesMatchMapLayersByName() {
        OtherSources o = new OtherSources(Arrays.asList(
                "/sdcard/atak/tools/datasync/CIP/S2Underground_Common_Intelligence_Picture_October_23_2026_Export_1.kmz",
                "/sdcard/atak/overlays/unrelated.kml", "/sdcard/atak/grg/photo.tif"));
        assertEquals(1, o.matching(Fixtures.e(
                "X/S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz", "c")).size());
        assertTrue(o.matching(Fixtures.e("X/Yemen FLOT.kmz", "y")).isEmpty());
    }

    @Test
    public void atomicFileKeepsOldContentWhenAWriteFails() throws Exception {
        File f = new File(tmp.getRoot(), "a/b.json");
        AtomicTextFile a = new AtomicTextFile(f);
        a.write("one");
        assertTrue(new File(f.getPath() + ".tmp").mkdirs());
        try {
            a.write("two");
        } catch (java.io.IOException expected) {
            // expected
        }
        assertEquals("one", a.read());
    }

    @Test
    public void backupHoldsThePreviousGoodVersion() throws Exception {
        File f = new File(tmp.getRoot(), "s.json");
        AtomicTextFile a = new AtomicTextFile(f);
        a.write("one");
        a.write("two");
        assertEquals("two", a.read());
        assertEquals("one", a.readBackup());
    }

    @Test
    public void mapLayerContentCheck() throws Exception {
        File kmz = Fixtures.zip(tmp.newFile("a.kmz"), Arrays.asList("doc.kml"));
        assertNull(MapLayerInspector.problem(kmz, "a.kmz"));
        File kml = tmp.newFile("a.kml");
        Files.write(kml.toPath(), "\uFEFF  <?xml version='1.0'?><kml/>".getBytes(StandardCharsets.UTF_8));
        assertNull(MapLayerInspector.problem(kml, "a.kml"));
        File html = tmp.newFile("b.kmz");
        Files.write(html.toPath(), "<html>404</html>".getBytes(StandardCharsets.UTF_8));
        assertTrue(MapLayerInspector.problem(html, "b.kmz").contains("not a KMZ"));
        File lfs = tmp.newFile("c.kmz");
        Files.write(lfs.toPath(), "version https://git-lfs.github.com/spec/v1\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(MapLayerInspector.problem(lfs, "c.kmz").contains("LFS"));
        assertTrue(MapLayerInspector.problem(tmp.newFile("empty.kml"), "empty.kml").contains("empty"));
    }
}
