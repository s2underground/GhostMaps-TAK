package com.atakmap.android.ghostmaps.plugin.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a GhostMaps file name into product name, date and version.
 *
 * <p>The repo's files are named like
 * {@code S2Underground_Common_Intelligence_Picture_October_27_2026_Export_1.kmz},
 * {@code Iran_Kinetic_Events_October_10_2026_v2.kmz},
 * {@code DC Curfew Zone_August 11_2025.kmz},
 * {@code Reno Wildfires August 24, 2026.kmz} or
 * {@code Persian Gulf Critical Infrastructure v5.kmz}. Underscores count as
 * spaces; the first "Month Day Year" (or ISO yyyy-mm-dd) is the date; a
 * trailing "v2", "V2" or "Export 2" is the version. What's left is the
 * product name.</p>
 */
public final class FileName {

    private static final String MONTHS =
            "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?"
                    + "|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?"
                    + "|dec(?:ember)?)";

    private static final Pattern MDY = Pattern.compile(
            "\\b" + MONTHS + "\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ISO = Pattern.compile(
            "\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");

    private static final Pattern VERSION = Pattern.compile(
            "\\s*\\b(?:export\\s*|v)(\\d{1,3})\\s*$",
            Pattern.CASE_INSENSITIVE);

    private final String product;
    private final int date; // yyyymmdd, 0 when the name has no date
    private final int version; // 0 when the name has no version

    private FileName(String product, int date, int version) {
        this.product = product;
        this.date = date;
        this.version = version;
    }

    /** Name with date and version removed, separators tidied. Never empty. */
    public String getProduct() {
        return product;
    }

    /** Date as yyyymmdd, or 0 if the name has none. */
    public int getDate() {
        return date;
    }

    public boolean hasDate() {
        return date != 0;
    }

    public int getVersion() {
        return version;
    }

    /**
     * Orders versions of one product: dated after undated, then by date, then
     * by version number. Returns negative when {@code this} is older.
     */
    public int compareAge(FileName other) {
        if (date != other.date)
            return date < other.date ? -1 : 1;
        if (version != other.version)
            return version < other.version ? -1 : 1;
        return 0;
    }

    public static FileName parse(String fileName) {
        String stem = fileName == null ? "" : fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0)
            stem = stem.substring(0, dot);
        String text = tidy(stem.replace('_', ' '));

        int date = 0;
        Matcher m = MDY.matcher(text);
        if (m.find()) {
            int month = monthNumber(m.group(1));
            int day = Integer.parseInt(m.group(2));
            int year = Integer.parseInt(m.group(3));
            if (valid(year, month, day)) {
                date = year * 10000 + month * 100 + day;
                text = text.substring(0, m.start()) + " " + text.substring(m.end());
            }
        } else {
            m = ISO.matcher(text);
            if (m.find()) {
                int year = Integer.parseInt(m.group(1));
                int month = Integer.parseInt(m.group(2));
                int day = Integer.parseInt(m.group(3));
                if (valid(year, month, day)) {
                    date = year * 10000 + month * 100 + day;
                    text = text.substring(0, m.start()) + " " + text.substring(m.end());
                }
            }
        }

        text = tidy(text);
        int version = 0;
        m = VERSION.matcher(text);
        if (m.find() && m.start() > 0) {
            version = Integer.parseInt(m.group(1));
            text = text.substring(0, m.start());
        }

        String product = tidy(text);
        // "…_August_17_2026_Export_v1": the bare "Export" left behind is
        // part of the version label, not the product name
        if (version > 0 || date != 0)
            product = product.replaceAll("(?i)\\s+export$", "");
        // drop separators left dangling where the date was cut out
        product = product.replaceAll("^[\\s,\\-–]+|[\\s,\\-–]+$", "");
        if (product.isEmpty())
            product = tidy(stem.replace('_', ' '));
        return new FileName(product, date, version);
    }

    private static String tidy(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }

    private static boolean valid(int y, int m, int d) {
        return y >= 1990 && y <= 2100 && m >= 1 && m <= 12 && d >= 1 && d <= 31;
    }

    private static int monthNumber(String name) {
        String n = name.toLowerCase(Locale.US);
        String[] prefixes = {
                "jan", "feb", "mar", "apr", "may", "jun",
                "jul", "aug", "sep", "oct", "nov", "dec"
        };
        for (int i = 0; i < prefixes.length; i++)
            if (n.startsWith(prefixes[i]))
                return i + 1;
        return 0;
    }
}
