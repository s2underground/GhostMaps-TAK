package com.atakmap.android.ghostmaps.plugin.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a published file name into a display name, a date and a version.
 *
 * <p>Used for display and for ordering versions of one product. It never
 * decides on its own that two files are the same map; see
 * {@link RepoEntry#groupKey()} for how products are scoped.</p>
 *
 * <p>Recognized dates (underscores count as spaces, case-insensitive):
 * {@code October_27_2026}, {@code Oct 27, 2026}, {@code August 8th 2026},
 * {@code 27 October 2026}, {@code 27OCT2026}, {@code 2026-10-27},
 * {@code 2026_10_27}, {@code 10-27-2026}, {@code 20261027} and month-year
 * forms such as {@code July_2025}. Dates must exist on the calendar. When a
 * name holds several dates (a range), the latest one is the file's date and
 * all are removed from the display name.</p>
 *
 * <p>Recognized versions: a trailing {@code v2} / {@code V2}, and a trailing
 * {@code Export 1} / {@code Export v2} when it follows a date (ArcGIS export
 * naming). A copy suffix such as {@code (1)} is ignored.</p>
 */
public final class FileName {

    private static final String MONTH =
            "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?"
                    + "|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?"
                    + "|dec(?:ember)?)";

    /** Ordered: specific patterns first so their spans aren't claimed by looser ones. */
    private static final Pattern[] DATE_PATTERNS = {
            // Month D, YYYY  /  Month Dth YYYY
            Pattern.compile("\\b" + MONTH + "\\.?\\s+(\\d{1,2})(?:st|nd|rd|th)?,?\\s+(\\d{4})\\b",
                    Pattern.CASE_INSENSITIVE),
            // D Month YYYY
            Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\s+" + MONTH + "\\.?,?\\s+(\\d{4})\\b",
                    Pattern.CASE_INSENSITIVE),
            // 27OCT2026
            Pattern.compile("\\b(\\d{1,2})" + MONTH + "(\\d{4})\\b", Pattern.CASE_INSENSITIVE),
            // 2026-10-27, 2026 10 27 (underscores were turned into spaces)
            Pattern.compile("\\b((?:19|20)\\d{2})[-. ](\\d{1,2})[-. ](\\d{1,2})\\b"),
            // 10-27-2026, 10/27/2026 (US order)
            Pattern.compile("\\b(\\d{1,2})[-/.](\\d{1,2})[-/.]((?:19|20)\\d{2})\\b"),
            // 20261027
            Pattern.compile("\\b((?:19|20)\\d{2})(\\d{2})(\\d{2})\\b"),
            // Month YYYY (no day)
            Pattern.compile("\\b" + MONTH + "\\.?,?\\s+(\\d{4})\\b", Pattern.CASE_INSENSITIVE),
    };

    private static final Pattern COPY_SUFFIX = Pattern.compile(
            "\\s*(?:\\(\\d{1,3}\\)|-\\s*copy(?:\\s*\\d+)?)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern V_VERSION = Pattern.compile(
            "\\s+v(\\d{1,3})\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern EXPORT_VERSION = Pattern.compile(
            "\\s+export\\s*(?:v\\s*)?(\\d{1,3})?\\s*$", Pattern.CASE_INSENSITIVE);

    private final String product;
    private final int date; // yyyymmdd; dd = 00 for month-year names; 0 = no date
    private final int version; // 0 = no version

    private FileName(String product, int date, int version) {
        this.product = product;
        this.date = date;
        this.version = version;
    }

    /** Name with dates, version and copy suffix removed. Never empty. */
    public String getProduct() {
        return product;
    }

    /** yyyymmdd (day 00 for month-year names), or 0 when the name has no date. */
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
     * Orders versions of one product: by date, then version number. Negative
     * when {@code this} is older. Only meaningful within one product group.
     */
    public int compareAge(FileName other) {
        if (date != other.date)
            return date < other.date ? -1 : 1;
        return Integer.compare(version, other.version);
    }

    public static FileName parse(String fileName) {
        String stem = fileName == null ? "" : fileName;
        int dot = stem.lastIndexOf('.');
        if (dot > 0)
            stem = stem.substring(0, dot);
        String fallback = tidy(stem.replace('_', ' '));
        String text = COPY_SUFFIX.matcher(fallback).replaceFirst("");

        // find every date; remember spans so they can be cut out
        int best = 0;
        StringBuilder work = new StringBuilder(text);
        for (Pattern p : DATE_PATTERNS) {
            Matcher m = p.matcher(work);
            List<int[]> spans = new ArrayList<>();
            while (m.find()) {
                int d = toDate(p, m);
                if (d != 0) {
                    best = Math.max(best, d);
                    spans.add(new int[] { m.start(), m.end() });
                }
            }
            // blank the spans (keep indices stable for the next pattern)
            for (int[] s : spans)
                for (int i = s[0]; i < s[1]; i++)
                    work.setCharAt(i, ' ');
        }
        text = tidy(work.toString());

        int version = 0;
        Matcher e = best != 0 ? EXPORT_VERSION.matcher(text) : null;
        if (e != null && e.find() && e.start() > 0) {
            // "…_Export_1", "…_Export_v2", "…_Export" after a date (ArcGIS exports)
            version = e.group(1) == null ? 0 : Integer.parseInt(e.group(1));
            text = text.substring(0, e.start());
        } else {
            Matcher v = V_VERSION.matcher(text);
            if (v.find() && v.start() > 0) {
                version = Integer.parseInt(v.group(1));
                text = text.substring(0, v.start());
            }
        }

        String product = tidy(text).replaceAll("^[\\s,\\-–]+|[\\s,\\-–]+$", "");
        if (product.isEmpty())
            product = fallback.isEmpty() ? "?" : fallback;
        return new FileName(product, best, version);
    }

    /** Converts one match to yyyymmdd, or 0 if it isn't a real calendar date. */
    private static int toDate(Pattern p, Matcher m) {
        int y, mo, d;
        if (p == DATE_PATTERNS[0]) {
            mo = month(m.group(1));
            d = Integer.parseInt(m.group(2));
            y = Integer.parseInt(m.group(3));
        } else if (p == DATE_PATTERNS[1]) {
            d = Integer.parseInt(m.group(1));
            mo = month(m.group(2));
            y = Integer.parseInt(m.group(3));
        } else if (p == DATE_PATTERNS[2]) {
            d = Integer.parseInt(m.group(1));
            mo = month(m.group(2));
            y = Integer.parseInt(m.group(3));
        } else if (p == DATE_PATTERNS[3] || p == DATE_PATTERNS[5]) {
            y = Integer.parseInt(m.group(1));
            mo = Integer.parseInt(m.group(2));
            d = Integer.parseInt(m.group(3));
        } else if (p == DATE_PATTERNS[4]) {
            mo = Integer.parseInt(m.group(1));
            d = Integer.parseInt(m.group(2));
            y = Integer.parseInt(m.group(3));
        } else {
            mo = month(m.group(1));
            y = Integer.parseInt(m.group(2));
            return y >= 1990 && y <= 2100 && mo >= 1 ? y * 10000 + mo * 100 : 0;
        }
        return isRealDate(y, mo, d) ? y * 10000 + mo * 100 + d : 0;
    }

    static boolean isRealDate(int y, int m, int d) {
        if (y < 1990 || y > 2100 || m < 1 || m > 12 || d < 1)
            return false;
        int[] days = { 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31 };
        boolean leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0;
        int max = (m == 2 && leap) ? 29 : days[m - 1];
        return d <= max;
    }

    private static int month(String name) {
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

    private static String tidy(String s) {
        return s.replaceAll("\\s+", " ").trim();
    }
}
