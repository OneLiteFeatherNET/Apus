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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Proves {@link Archives#extractTar} resists the same class of attack that {@link
 * net.onelitefeather.apus.ingest.LayoutDetector} was found and hardened against (see that
 * class's Javadoc): a tar archive is untrusted input (a Pterodactyl backup, or an object fetched
 * from S3), so an entry name must never be able to write outside the target directory, and a
 * symlink-typed entry must never be materialised as a real filesystem symlink.
 *
 * <p>Both protections already existed in {@link Archives} before this test was written --
 * {@code resolveSafely} normalises and contains every entry path, and neither {@code extractTar}
 * nor {@link TarStreamReader} ever calls {@code Files.createSymbolicLink}, so a {@code typeflag
 * '2'} entry is written as an inert regular file rather than a link. These tests exist as
 * regression coverage for that behaviour, not as a fix for a bug found here.
 */
class ArchivesTest {

    @Test
    void extractTarRejectsAnEntryThatEscapesTheTargetDirectoryViaDotDotSegments(@TempDir Path targetDir)
            throws IOException {
        byte[] tar = new TestTarBuilder().addFile("../evil.txt", "pwned").toTarBytes();

        IOException thrown = assertThrows(
                IOException.class,
                () -> Archives.extractTar(new ByteArrayInputStream(tar), targetDir, entryName -> true));
        assertTrue(
                thrown.getMessage().contains("escapes target directory"),
                "expected a path-escape message, got: " + thrown.getMessage());

        assertFalse(
                Files.exists(targetDir.getParent().resolve("evil.txt")),
                "the '..' entry must not have landed a file next to the target directory");
    }

    @Test
    void extractTarDoesNotMaterializeATarSymlinkEntryAsARealSymlink(@TempDir Path targetDir) throws IOException {
        byte[] tar = new TestTarBuilder().addSymlink("escape-link", "/etc/passwd").toTarBytes();

        Archives.extractTar(new ByteArrayInputStream(tar), targetDir, entryName -> true);

        Path written = targetDir.resolve("escape-link");
        assertTrue(Files.exists(written), "the entry is still materialised, just not as a link");
        assertFalse(
                Files.isSymbolicLink(written),
                "a tar symlink entry must never become a real filesystem symlink pointing outside the target");
        assertEquals(0L, Files.size(written), "the reader does not (yet) resolve link targets as content");
    }

    // --- S2: bounded extraction (zip-bomb protection) --------------------------------------

    @Test
    void extractTarAbortsOnceTheTotalSizeLimitIsExceeded(@TempDir Path targetDir) throws IOException {
        byte[] tar = new TestTarBuilder()
                .addFile("world/region/r.0.0.mca", "0123456789") // 10 bytes
                .toTarBytes();

        Archives.Limits limits = new Archives.Limits(5, Long.MAX_VALUE); // smaller than the one entry

        IOException thrown = assertThrows(
                IOException.class,
                () -> Archives.extractTar(new ByteArrayInputStream(tar), targetDir, entryName -> true, limits));
        assertTrue(
                thrown.getMessage().contains("total size limit"),
                "expected a total-size-limit message, got: " + thrown.getMessage());
    }

    @Test
    void extractTarAbortsOnceTheEntryCountLimitIsExceeded(@TempDir Path targetDir) throws IOException {
        byte[] tar = new TestTarBuilder()
                .addFile("world/region/r.0.0.mca", "a")
                .addFile("world/region/r.0.1.mca", "b")
                .addFile("world/region/r.0.2.mca", "c")
                .toTarBytes();

        Archives.Limits limits = new Archives.Limits(Long.MAX_VALUE, 2);

        IOException thrown = assertThrows(
                IOException.class,
                () -> Archives.extractTar(new ByteArrayInputStream(tar), targetDir, entryName -> true, limits));
        assertTrue(
                thrown.getMessage().contains("entry limit"), "expected an entry-limit message, got: " + thrown.getMessage());
    }

    @Test
    void extractTarWithinLimitsSucceedsNormally(@TempDir Path targetDir) throws IOException {
        byte[] tar = new TestTarBuilder().addFile("world/region/r.0.0.mca", "content").toTarBytes();

        Archives.extractTar(new ByteArrayInputStream(tar), targetDir, entryName -> true, new Archives.Limits(1024, 10));

        assertTrue(Files.exists(targetDir.resolve("world/region/r.0.0.mca")));
    }

    @Test
    void limitsFromReadsConfiguredValuesAndDefaultsToUnboundedWhenAbsent() {
        Archives.Limits configured = Archives.limitsFrom(
                Map.of(Archives.CONFIG_MAX_TOTAL_BYTES, "42", Archives.CONFIG_MAX_ENTRIES, "7"));
        assertEquals(42L, configured.maxTotalBytes());
        assertEquals(7L, configured.maxEntries());

        Archives.Limits defaulted = Archives.limitsFrom(Map.of());
        assertEquals(Long.MAX_VALUE, defaulted.maxTotalBytes());
        assertEquals(Long.MAX_VALUE, defaulted.maxEntries());
    }
}
