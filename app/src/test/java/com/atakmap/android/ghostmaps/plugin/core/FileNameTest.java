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
    public void publishedNamingStyles() {
        check("S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz",
                "S2Underground Common Intelligence Picture", 20261027, 1);
        check("S2Underground_Border_Crisis_Map_June_02_2026_Export_v2.kmz",
                "S2Underground Border Crisis Map", 20260602, 2);
        check("Iran_Kinetic_Events_October_10_2026_v2.kmz", "Iran Kinetic Events", 20261010, 2);
        check("Russia_Centers_of_Gravity_July_06_2026_V2.kmz", "Russia Centers of Gravity", 20260706, 2);
        check("DC Curfew Zone_August 11_2025.kmz", "DC Curfew Zone", 20250811, 0);
        check("Reno Wildfires August 24, 2026.kmz", "Reno Wildfires", 20260824, 0);
        check("Venezuelan_Crisis_Sep_30_2025_v1.kmz", "Venezuelan Crisis", 20250930, 1);
        check("NIFC_Current_Wildfires__SPOKANE_August_8_2026.kmz", "NIFC Current Wildfires SPOKANE", 20260808, 0);
        check("Persian Gulf Critical Infrastructure v5.kmz", "Persian Gulf Critical Infrastructure", 0, 5);
        check("Kinetic_Activities_Last_30_Days.kmz", "Kinetic Activities Last 30 Days", 0, 0);
        check("S2U Map Icons.zip", "S2U Map Icons", 0, 0);
    }

    @Test
    public void otherDateFormats() {
        check("CIP_2026_10_27.kmz", "CIP", 20261027, 0);
        check("CIP_2026-10-27.kmz", "CIP", 20261027, 0);
        check("CIP_10-27-2026.kmz", "CIP", 20261027, 0);
        check("CIP_27OCT2026.kmz", "CIP", 20261027, 0);
        check("CIP_20261027.kmz", "CIP", 20261027, 0);
        check("CIP 27 October 2026.kmz", "CIP", 20261027, 0);
        check("Fires August 8th, 2026.kmz", "Fires", 20260808, 0);
    }

    @Test
    public void monthYearNames() {
        check("TexasFloods_July_2025_v1.kmz", "TexasFloods", 20250700, 1);
        check("TexasFloods_August_2025_v1.kmz", "TexasFloods", 20250800, 1);
        check("May 2026 Primary Election.kmz", "Primary Election", 20260500, 0);
        assertTrue(FileName.parse("X_August_2025.kmz").compareAge(FileName.parse("X_July_2025.kmz")) > 0);
        // a dated day in the month sorts after the bare month
        assertTrue(FileName.parse("X_July_9_2025.kmz").compareAge(FileName.parse("X_July_2025.kmz")) > 0);
    }

    @Test
    public void invalidCalendarDatesAreNotDates() {
        check("Feb_31_2026_X.kmz", "Feb 31 2026 X", 0, 0);
        check("CIP_2026_13_01.kmz", "CIP 2026 13 01", 0, 0);
        assertTrue(FileName.isRealDate(2028, 2, 29));
        assertFalse(FileName.isRealDate(2026, 2, 29));
    }

    @Test
    public void rangesUseTheLatestDate() {
        check("Fires_June_1_2026_to_June_30_2026.kmz", "Fires to", 20260630, 0);
    }

    @Test
    public void copySuffixIsIgnored() {
        check("Iran_Kinetic_Events_October_10_2026_v1 (1).kmz", "Iran Kinetic Events", 20261010, 1);
        check("Yemen FLOT - Copy.kmz", "Yemen FLOT", 0, 0);
    }

    @Test
    public void exportIsAVersionOnlyAfterADate() {
        check("Vehicle Export 2.kmz", "Vehicle Export 2", 0, 0);
        check("Export 2.kmz", "Export 2", 0, 0);
    }

    @Test
    public void wordsThatLookLikeMonthsAreNotDates() {
        check("Mayday_Routes.kmz", "Mayday Routes", 0, 0);
        check("Mar_Lago_Perimeter.kmz", "Mar Lago Perimeter", 0, 0);
        check("Census_2020.kmz", "Census 2020", 0, 0);
    }

    @Test
    public void versionAloneKeepsTheName() {
        check("v2.kmz", "v2", 0, 0);
        check("Route_v66.kmz", "Route", 0, 66);
    }

    @Test
    public void ageOrdering() {
        FileName v1 = FileName.parse("China_PLA_Centers_of_Gravity_July_4_2026.kmz");
        FileName v2 = FileName.parse("China_PLA_Centers_of_Gravity_July_4_2026_v2.kmz");
        FileName older = FileName.parse("China_PLA_Centers_of_Gravity_March_17_2026.kmz");
        assertTrue(v2.compareAge(v1) > 0);
        assertTrue(v1.compareAge(older) > 0);
        assertEquals(0, v1.compareAge(FileName.parse("China PLA Centers of Gravity July 4 2026.kml")));
    }
}
