/**
 * Apus - render and host BlueMap maps on Kubernetes.
 * Copyright (C) 2026 OneLiteFeather and contributors
 * <p>
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * <p>
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package net.onelitefeather.apus.ingest.connector;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Direct tests of the hand-rolled tar reader, independent of any HTTP or S3 plumbing, since it is
 * the one piece of genuinely novel parsing logic in this package.
 */
class TarStreamReaderTest {

    @Test
    void readsARegularFileEntryBackByteForByte() throws IOException {
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        byte[] tar = new TestTarBuilder().addFile("greeting.txt", content).toTarBytes();

        try (TarStreamReader reader = new TarStreamReader(new ByteArrayInputStream(tar))) {
            TarStreamReader.Entry entry = reader.nextEntry();
            assertEquals("greeting.txt", entry.name());
            assertFalse(entry.directory());
            assertEquals(content.length, entry.size());

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            reader.transferTo(out);
            assertArrayEquals(content, out.toByteArray());

            assertNull(reader.nextEntry(), "the end-of-archive marker must surface as null");
        }
    }

    @Test
    void reportsDirectoryEntriesAsDirectoriesWithNoContentToRead() throws IOException {
        byte[] tar = new TestTarBuilder().addDirectory("world").toTarBytes();

        try (TarStreamReader reader = new TarStreamReader(new ByteArrayInputStream(tar))) {
            TarStreamReader.Entry entry = reader.nextEntry();
            assertEquals("world/", entry.name());
            assertTrue(entry.directory());
            assertEquals(0, entry.size());
        }
    }

    @Test
    void readsMultipleEntriesInOrderEvenWhenAnEarlierEntrysContentIsNeverConsumed() throws IOException {
        byte[] first = "first-file-content".getBytes(StandardCharsets.UTF_8);
        byte[] second = "second-file-content".getBytes(StandardCharsets.UTF_8);
        byte[] tar = new TestTarBuilder()
                .addFile("a.txt", first)
                .addFile("b.txt", second)
                .toTarBytes();

        try (TarStreamReader reader = new TarStreamReader(new ByteArrayInputStream(tar))) {
            TarStreamReader.Entry entryA = reader.nextEntry();
            assertEquals("a.txt", entryA.name());
            // Deliberately skip transferTo() here -- nextEntry() must still land correctly on
            // "b.txt" by skipping the unread content and padding itself. This is exactly the
            // "walk once, skip what you don't want" behaviour the Pterodactyl connector relies on
            // to avoid ever landing the full archive on disk.

            TarStreamReader.Entry entryB = reader.nextEntry();
            assertEquals("b.txt", entryB.name());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            reader.transferTo(out);
            assertArrayEquals(second, out.toByteArray());

            assertNull(reader.nextEntry());
        }
    }

    @Test
    void reconstructsAGnuLongNameEntryLongerThanTheHundredByteHeaderField() throws IOException {
        String longName =
                "plugins/SomeReallyLongPluginNameThatDefinitelyExceedsTheClassicHundredByteUstarNameFieldLimitByAWideMargin/config.yml";
        assertTrue(longName.length() > 100, "test setup must actually exercise the long-name path");
        byte[] content = "key: value".getBytes(StandardCharsets.UTF_8);
        byte[] tar = new TestTarBuilder().addFileWithLongName(longName, content).toTarBytes();

        try (TarStreamReader reader = new TarStreamReader(new ByteArrayInputStream(tar))) {
            TarStreamReader.Entry entry = reader.nextEntry();
            assertEquals(longName, entry.name());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            reader.transferTo(out);
            assertArrayEquals(content, out.toByteArray());
        }
    }

    @Test
    void anEmptyArchiveYieldsNoEntries() throws IOException {
        byte[] tar = new TestTarBuilder().toTarBytes();

        try (TarStreamReader reader = new TarStreamReader(new ByteArrayInputStream(tar))) {
            assertNull(reader.nextEntry());
        }
    }
}
