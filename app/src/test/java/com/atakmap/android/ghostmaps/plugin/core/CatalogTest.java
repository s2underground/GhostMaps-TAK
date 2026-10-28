package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Runs against a snapshot of the real repo listing (Oct 28, 2026). */
public class CatalogTest {

    private static final String CIP = "ArcGIS Data for ATAK (KMZs)/Common Intelligence Picture/Master Database/";
    private static final String IRAN = "Incident Response Products for ATAK (KMZs and Documents)/Iran 2026/Iran Kinetic Events/";

    private static TreeParser.Result result;
    private static Catalog catalog;

    @BeforeClass
    public static void load() throws Exception {
        result = TreeParser.parse(readResource("/ghostmaps-tree-2026-10-28.json"));
        catalog = new Catalog(result.files);
    }

    static String readResource(String name) throws Exception {
        try (InputStream in = CatalogTest.class.getResourceAsStream(name)) {
            assertNotNull(name, in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0)
                out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void parsesBlobsOnlyAndSkipsReadme() {
        // 166 blobs in the repo; README.md is not importable
        assertEquals(165, catalog.size());
        assertFalse(result.truncated);
        assertTrue(catalog.find("README.md") == null);
        RepoFile big = catalog.find("ArcGIS Data for ATAK (KMZs)/BaseData/Demographics/USA_Census_Ethnicity_Data_2020.kmz");
        assertNotNull(big);
        assertTrue(big.getSize() > 60_000_000L);
        assertEquals(40, big.getSha().length());
    }

    @Test
    public void latestCipIsOctober27() {
        RepoFile old = catalog.find(CIP + "S2Underground_Common_Intelligence_Picture_July_03_2026_Export_2.kmz");
        assertNotNull(old);
        RepoFile latest = catalog.latestOf(old);
        assertEquals(CIP + "S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz",
                latest.getPath());
        // every daily export is a version of the same product
        assertEquals(51, catalog.versionsOf(old).size());
    }

    @Test
    public void sameDayV2BeatsV1() {
        RepoFile v1 = catalog.find(IRAN + "Iran_Kinetic_Events_October_10_2026_v1.kmz");
        assertNotNull(v1);
        assertEquals(IRAN + "Iran_Kinetic_Events_October_15_2026_v1.kmz",
                catalog.latestOf(v1).getPath());
        List<RepoFile> versions = catalog.versionsOf(v1);
        int i15 = indexOf(versions, "Iran_Kinetic_Events_October_15_2026_v1.kmz");
        int i10v2 = indexOf(versions, "Iran_Kinetic_Events_October_10_2026_v2.kmz");
        int i10v1 = indexOf(versions, "Iran_Kinetic_Events_October_10_2026_v1.kmz");
        assertTrue(i15 < i10v2 && i10v2 < i10v1);
    }

    @Test
    public void latestListHasOneRowPerProductAndNoArchive() {
        List<RepoFile> latest = catalog.latest(null);
        for (RepoFile f : latest)
            assertFalse(f.getPath(), f.isArchived());
        int cip = 0;
        for (RepoFile f : latest)
            if (f.getPath().startsWith(CIP))
                cip++;
        assertEquals(1, cip);
        // newest first: the Oct 27 CIP is the newest dated file in the repo
        assertEquals(CIP + "S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz",
                latest.get(0).getPath());
    }

    @Test
    public void filterMatchesEveryWord() {
        List<RepoFile> hits = catalog.latest("iran kinetic");
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).getPath().endsWith("Iran_Kinetic_Events_October_15_2026_v1.kmz"));
        assertTrue(catalog.search("spokane fire").size() >= 2);
        assertEquals(0, catalog.search("no such thing").size());
    }

    @Test
    public void archiveDetection() {
        assertTrue(catalog.find("Incident Response Products for ATAK (KMZs and Documents)/0_Archive/Texas Floods - July, 2025/TexasFloods_July_2025_v1.kmz") != null);
        assertTrue(new RepoFile("Incident Response Products for ATAK (KMZs and Documents)/0_Archive/Texas Floods - July, 2025/TexasFloods_July_2025_v1.kmz", "", 1).isArchived());
        assertFalse(new RepoFile(CIP + "x.kmz", "", 1).isArchived());
    }

    @Test
    public void folderBrowsing() {
        Map<String, Integer> top = catalog.subfolders("");
        assertEquals(2, top.size());
        assertEquals(Integer.valueOf(68), top.get("ArcGIS Data for ATAK (KMZs)"));
        Map<String, Integer> cip = catalog.subfolders("ArcGIS Data for ATAK (KMZs)/Common Intelligence Picture");
        assertEquals(2, cip.size());
        assertEquals(Integer.valueOf(51), cip.get("ArcGIS Data for ATAK (KMZs)/Common Intelligence Picture/Master Database"));
        // a file that sits directly in a top-level folder
        List<RepoFile> loose = catalog.filesIn("Incident Response Products for ATAK (KMZs and Documents)");
        assertEquals(1, loose.size());
        assertEquals("Reno Wildfires August 24, 2026.kmz", loose.get(0).fileName());
    }

    private static int indexOf(List<RepoFile> list, String name) {
        for (int i = 0; i < list.size(); i++)
            if (list.get(i).fileName().equals(name))
                return i;
        return -1;
    }
}
