package com.atakmap.android.ghostmaps.plugin.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class FormatsAndHashTest {

    @Test
    public void sizes() {
        assertEquals("512 B", Formats.size(512));
        assertEquals("39 KB", Formats.size(39925));
        assertEquals("3.4 MB", Formats.size(3_565_000));
        assertEquals("62 MB", Formats.size(65_000_000));
        assertEquals("?", Formats.size(-1));
    }

    @Test
    public void dates() {
        assertEquals("Oct 27, 2026", Formats.date(20261027));
        assertEquals("Aug 8, 2026", Formats.date(20260808));
        assertEquals("", Formats.date(0));
    }

    @Test
    public void folders() {
        assertEquals("ArcGIS Data for ATAK › Common Intelligence Picture › Master Database",
                Formats.folder("ArcGIS Data for ATAK (KMZs)/Common Intelligence Picture/Master Database"));
        assertEquals("Master Database",
                Formats.folderName("ArcGIS Data for ATAK (KMZs)/Common Intelligence Picture/Master Database"));
        assertEquals("Incident Response Products for ATAK",
                Formats.folder("Incident Response Products for ATAK (KMZs and Documents)"));
        assertEquals("", Formats.folder(""));
    }

    @Test
    public void versionDetail() {
        assertEquals("Oct 10, 2026 · v2", Formats.version(FileName.parse("Iran_Kinetic_Events_October_10_2026_v2.kmz")));
        assertEquals("Oct 27, 2026", Formats.version(FileName.parse("X_October_27_2026_Export_1.kmz")));
        assertEquals("v5", Formats.version(FileName.parse("Persian Gulf Critical Infrastructure v5.kmz")));
    }

    @Test
    public void gitBlobHashMatchesGit() {
        // `printf 'hello\n' | git hash-object --stdin`
        byte[] data = "hello\n".getBytes(StandardCharsets.US_ASCII);
        GitBlobHasher h = new GitBlobHasher(data.length);
        h.update(data, 0, 3);
        h.update(data, 3, data.length - 3);
        assertTrue(h.matches("ce013625030ba8dba906f756967f9e9ca394464a"));
    }

    @Test
    public void gitBlobHashRejectsShortOrWrongData() {
        byte[] data = "hello\n".getBytes(StandardCharsets.US_ASCII);
        GitBlobHasher shortRead = new GitBlobHasher(data.length);
        shortRead.update(data, 0, 4);
        assertFalse(shortRead.matches("ce013625030ba8dba906f756967f9e9ca394464a"));
        GitBlobHasher wrong = new GitBlobHasher(data.length);
        wrong.update("HELLO\n".getBytes(StandardCharsets.US_ASCII), 0, 6);
        assertFalse(wrong.matches("ce013625030ba8dba906f756967f9e9ca394464a"));
    }
}
