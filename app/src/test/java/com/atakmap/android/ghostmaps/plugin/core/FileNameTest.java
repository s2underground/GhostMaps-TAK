package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FileNameTest {

    private static void check(String file, String product, int date, int version) {
        FileName n = FileName.parse(file);
        assertEquals(file + " product", product, n.getProduct());
        assertEquals(file + " date", date, n.getDate());
        assertEquals(file + " version", version, n.getVersion());
    }

    @Test
    public void cipExport() {
        check("S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz",
                "S2Underground Common Intelligence Picture", 20261027, 1);
        check("S2Underground_Common_Intelligence_Picture_October_1_2026_Export_1.kmz",
                "S2Underground Common Intelligence Picture", 20261001, 1);
    }

    @Test
    public void exportThenVersion() {
        check("S2Underground_Border_Crisis_Map_August_17_2026_Export_v1.kmz",
                "S2Underground Border Crisis Map", 20260817, 1);
        check("S2Underground_Border_Crisis_Map_June_02_2026_Export_v2.kmz",
                "S2Underground Border Crisis Map", 20260602, 2);
    }

    @Test
    public void lowerAndUpperV() {
        check("Iran_Kinetic_Events_October_10_2026_v2.kmz", "Iran Kinetic Events", 20261010, 2);
        check("Russia_Centers_of_Gravity_July_06_2026_V2.kmz", "Russia Centers of Gravity", 20260706, 2);
    }

    @Test
    public void spacesAndCommasInDate() {
        check("DC Curfew Zone_August 11_2025.kmz", "DC Curfew Zone", 20250811, 0);
        check("Reno Wildfires August 24, 2026.kmz", "Reno Wildfires", 20260824, 0);
        check("Middle East Master IPB_February_18_2026.kmz", "Middle East Master IPB", 20260218, 0);
    }

    @Test
    public void abbreviatedMonth() {
        check("Venezuelan_Crisis_Sep_30_2025_v1.kmz", "Venezuelan Crisis", 20250930, 1);
    }

    @Test
    public void versionWithoutDate() {
        check("Persian Gulf Critical Infrastructure v5.kmz", "Persian Gulf Critical Infrastructure", 0, 5);
        check("IRGCN Minefield v2.kmz", "IRGCN Minefield", 0, 2);
        check("IRGCN Minefield.kmz", "IRGCN Minefield", 0, 0);
    }

    @Test
    public void noDateNoVersion() {
        check("Kinetic_Activities_Last_30_Days.kmz", "Kinetic Activities Last 30 Days", 0, 0);
        check("Garden Grove Evac Zone1.kmz", "Garden Grove Evac Zone1", 0, 0);
        check("S2U Map Icons.zip", "S2U Map Icons", 0, 0);
    }

    @Test
    public void suffixAfterDateIsKeptInProduct() {
        // "SPOKANE" and "WA" pulls are different products from the national one
        check("NIFC_Current_Wildfires__SPOKANE_August_8_2026.kmz",
                "NIFC Current Wildfires SPOKANE", 20260808, 0);
        check("NIFC_Current_Wildfires_August_8_2026.kmz",
                "NIFC Current Wildfires", 20260808, 0);
    }

    @Test
    public void isoDate() {
        check("Fire_Perimeters_2026-08-08_v3.kml", "Fire Perimeters", 20260808, 3);
    }

    @Test
    public void wordsThatLookLikeMonthsAreNotDates() {
        check("Mayday_Routes.kmz", "Mayday Routes", 0, 0);
        check("March_2026_Overview.kmz", "March 2026 Overview", 0, 0);
    }

    @Test
    public void versionAloneIsNotTheWholeName() {
        // a file literally called "v2" keeps its name
        check("v2.kmz", "v2", 0, 0);
    }

    @Test
    public void ageOrdering() {
        FileName undated = FileName.parse("Yemen FLOT.kmz");
        FileName dated = FileName.parse("Yemen FLOT_October_10_2026.kmz");
        FileName v1 = FileName.parse("China_PLA_Centers_of_Gravity_July_4_2026.kmz");
        FileName v2 = FileName.parse("China_PLA_Centers_of_Gravity_July_4_2026_v2.kmz");
        FileName older = FileName.parse("China_PLA_Centers_of_Gravity_March_17_2026.kmz");
        assertEquals(undated.getProduct(), dated.getProduct());
        assertTrue(dated.compareAge(undated) > 0);
        assertTrue(v2.compareAge(v1) > 0);
        assertTrue(v1.compareAge(older) > 0);
        assertFalse(older.compareAge(v2) > 0);
    }
}
