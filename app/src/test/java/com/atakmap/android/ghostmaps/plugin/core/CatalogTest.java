package com.atakmap.android.ghostmaps.plugin.core;

import static com.atakmap.android.ghostmaps.plugin.core.Fixtures.cat;
import static com.atakmap.android.ghostmaps.plugin.core.Fixtures.e;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.Map;

/** Product grouping, archives and browsing, on synthetic and real listings. */
public class CatalogTest {

    private static final String CIP = "ArcGIS Data for ATAK (KMZs)/Common Intelligence Picture/Master Database/";

    @Test
    public void realListingParsesInstallableFilesOnly() throws Exception {
        TreeParser.Result r = TreeParser.parse(Fixtures.resource(Fixtures.MORNING_OCT_28), Fixtures.COMMIT);
        assertFalse(r.truncated);
        assertEquals(165, r.catalog.size()); // 166 blobs minus README.md
        assertNull(r.catalog.find("README.md"));
        assertEquals(Fixtures.COMMIT, r.catalog.commitSha());
        RepoEntry icons = r.catalog.find("Incident Response Products for ATAK (KMZs and Documents)/Reference Material/S2U Map Icons.zip");
        assertNotNull(icons);
        assertEquals(FileKind.ICON_SET, icons.kind());
    }

    @Test
    public void dailyExportsAreOneProduct() throws Exception {
        Catalog c = Fixtures.real(Fixtures.MORNING_OCT_28);
        RepoEntry old = c.find(CIP + "S2Underground_Common_Intelligence_Picture_July_03_2026_Export_2.kmz");
        assertEquals(51, c.versionsOf(old).size());
        assertEquals(CIP + "S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz",
                c.newestLive(old.groupKey()).path());
        int cipRows = 0;
        for (RepoEntry e : c.latest(null))
            if (e.path().startsWith(CIP))
                cipRows++;
        assertEquals(1, cipRows);
    }

    @Test
    public void archivedVersionsStayInTheirProductButNeverLead() throws Exception {
        Catalog c = Fixtures.real(Fixtures.MORNING_OCT_29);
        String base = "Incident Response Products for ATAK (KMZs and Documents)/Iran 2026/Iran Kinetic Events/";
        RepoEntry archived = c.find(base + "Archive/Iran_Kinetic_Events_October_12_2026_v1.kmz");
        assertNotNull(archived);
        assertTrue(archived.isArchived());
        RepoEntry newest = c.newestLive(archived.groupKey());
        assertNotNull(newest);
        assertFalse(newest.isArchived());
        assertEquals(base.substring(0, base.length() - 1), newest.folder());
        for (RepoEntry e : c.latest(null))
            assertFalse(e.path(), e.isArchived());
    }

    @Test
    public void productsAreScopedToTheirFolder() {
        // P10: generic names in two incidents are two products
        Catalog c = cat(e("Spokane Fire/Evacuation_Zones_August_8_2026.kmz", "s1"),
                e("Reno Fire/Evacuation_Zones_August_24_2026.kmz", "r1"));
        assertEquals(2, c.latest(null).size());
        assertNotEquals(c.entries().get(0).groupKey(), c.entries().get(1).groupKey());
    }

    @Test
    public void rollingFileIsNotADatedSnapshotsVersion() {
        // P2: an undated rolling file and a dated snapshot are different products
        Catalog c = cat(e("CIP/Kinetic_Activities_Last_30_Days.kmz", "r1"),
                e("CIP/Archive/Kinetic_Activities_Last_30_Days_October_1_2026.kmz", "old"));
        List<RepoEntry> latest = c.latest(null);
        assertEquals(1, latest.size());
        assertEquals("CIP/Kinetic_Activities_Last_30_Days.kmz", latest.get(0).path());
    }

    @Test
    public void newestVersionArchivedLeavesTheLiveOneInLatest() {
        // P6: an accidentally archived newer file doesn't hide the live one
        Catalog c = cat(e("Live/X_October_1_2026.kmz", "x1"), e("Live/0_Archive/X_October_5_2026.kmz", "x2"));
        assertEquals("Live/X_October_1_2026.kmz", c.latest(null).get(0).path());
    }

    @Test
    public void kindsNeverMix() {
        // P12: an icon ZIP and a KMZ with similar names aren't versions of each other
        Catalog c = cat(e("Ref/S2U Map Icons.zip", "z1"), e("Ref/S2U_Map_Icons_October_1_2026.kmz", "k1"));
        assertEquals(2, c.latest(null).size());
    }

    @Test
    public void archiveFolderNames() {
        String[] yes = { "Archive", "0_Archive", "Archives", "Archived Incidents", "2025_Archive",
                "Archive (old)", "Old Archive", "1 - Archive" };
        String[] no = { "Archaeology", "Research Archive Index", "Superseded", "Basedata" };
        for (String s : yes)
            assertTrue(s, RepoEntry.isArchiveFolderName(s));
        for (String s : no)
            assertFalse(s, RepoEntry.isArchiveFolderName(s));
    }

    @Test
    public void scopeFolderDropsArchiveLevels() {
        RepoEntry a = e("Incidents/0_Archive/Texas Floods - July, 2025/TexasFloods_July_2025_v1.kmz", "t");
        RepoEntry b = e("Incidents/Texas Floods - July, 2025/TexasFloods_August_2025_v1.kmz", "u");
        assertEquals("Incidents/Texas Floods - July, 2025", a.scopeFolder());
        assertEquals(a.groupKey(), b.groupKey());
    }

    @Test
    public void filterAndBrowse() throws Exception {
        Catalog c = Fixtures.real(Fixtures.MORNING_OCT_28);
        assertEquals(1, c.latest("iran kinetic").size());
        assertTrue(c.search("spokane fire").size() >= 2);
        assertEquals(0, c.search("no such thing").size());
        Map<String, Integer> top = c.subfolders("");
        assertEquals(2, top.size());
        assertEquals(Integer.valueOf(68), top.get("ArcGIS Data for ATAK (KMZs)"));
        assertTrue(c.hasFolder("ArcGIS Data for ATAK (KMZs)/Common Intelligence Picture"));
        assertFalse(c.hasFolder("Nope"));
        assertEquals(1, c.filesIn("Incident Response Products for ATAK (KMZs and Documents)").size());
    }
}
