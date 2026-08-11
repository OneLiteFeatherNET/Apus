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
package net.onelitefeather.apus.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the fachlicher Kern of this module: which region files count as "changed" between two
 * runs, that {@link CopyState} correctly carries forward across separate {@link
 * IncrementalWorldCopier} calls (simulating separate push cycles), and that a failure partway
 * through a copy never leaves a half-written file in the staging directory.
 */
class IncrementalWorldCopierTest {

    private final IncrementalWorldCopier copier = new IncrementalWorldCopier();

    @TempDir
    Path tmp;

    private Path regionDir;
    private Path stagingRoot;

    @BeforeEach
    void setUp() throws IOException {
        regionDir = Files.createDirectories(tmp.resolve("world/region"));
        stagingRoot = tmp.resolve("staging");
    }

    @Test
    void copiesEveryRegionFileOnTheFirstRun() throws IOException {
        writeRegionFile("r.0.0.mca", "alpha");
        writeRegionFile("r.0.1.mca", "beta");

        CopyResult result = copier.copyChanged(dimensions(), stagingRoot, CopyState.empty());

        assertEquals(2, result.copiedRelativePaths().size());
        assertEquals(0, result.unchangedCount());
        assertTrue(result.copiedRelativePaths().contains("world/region/r.0.0.mca"));
        assertTrue(result.copiedRelativePaths().contains("world/region/r.0.1.mca"));
        assertEquals("alpha", Files.readString(stagingRoot.resolve("world/region/r.0.0.mca")));
        assertEquals("beta", Files.readString(stagingRoot.resolve("world/region/r.0.1.mca")));
    }

    @Test
    void secondRunWithNoChangesCopiesNothing() throws IOException {
        writeRegionFile("r.0.0.mca", "alpha");
        CopyState state = CopyState.empty();
        copier.copyChanged(dimensions(), stagingRoot, state);

        CopyResult second = copier.copyChanged(dimensions(), stagingRoot, state);

        assertTrue(second.isEmpty());
        assertEquals(1, second.unchangedCount());
    }

    @Test
    void onlyTheChangedFileIsCopiedOnASecondRun() throws IOException {
        writeRegionFile("r.0.0.mca", "alpha");
        writeRegionFile("r.0.1.mca", "beta");
        CopyState state = CopyState.empty();
        copier.copyChanged(dimensions(), stagingRoot, state);

        // Modify only one file, moving both its content and its mtime forward.
        writeRegionFile("r.0.1.mca", "beta-v2");
        bumpMtime(regionDir.resolve("r.0.1.mca"));

        CopyResult second = copier.copyChanged(dimensions(), stagingRoot, state);

        assertEquals(List.of("world/region/r.0.1.mca"), second.copiedRelativePaths());
        assertEquals(1, second.unchangedCount());
        assertEquals("beta-v2", Files.readString(stagingRoot.resolve("world/region/r.0.1.mca")));
    }

    @Test
    void aTouchedFileWithUnchangedContentIsNotRecopied() throws IOException {
        writeRegionFile("r.0.0.mca", "alpha");
        CopyState state = CopyState.empty();
        copier.copyChanged(dimensions(), stagingRoot, state);

        // Rewrite with byte-identical content but a new mtime -- e.g. an atomic region-file
        // rewrite that happens to produce the same bytes.
        writeRegionFile("r.0.0.mca", "alpha");
        bumpMtime(regionDir.resolve("r.0.0.mca"));

        CopyResult second = copier.copyChanged(dimensions(), stagingRoot, state);

        assertTrue(second.isEmpty(), "identical content must not be re-copied even if mtime moved");
        // But the cheap-check fields must have been refreshed, so a genuinely-unrelated later
        // touch doesn't get misdiagnosed against a stale mtime.
        RegionFileState refreshed = state.get("world/region/r.0.0.mca");
        assertEquals(Files.getLastModifiedTime(regionDir.resolve("r.0.0.mca")).toMillis(), refreshed.lastModifiedMillis());
    }

    @Test
    void stateCarriesForwardAcrossASimulatedProcessRestart() throws IOException {
        writeRegionFile("r.0.0.mca", "alpha");
        Path stateFile = tmp.resolve("push-state.properties");

        CopyState first = CopyState.load(stateFile);
        copier.copyChanged(dimensions(), stagingRoot, first);
        first.save(stateFile);

        // Simulate a fresh process: reload state from disk instead of reusing the in-memory object.
        CopyState reloaded = CopyState.load(stateFile);
        CopyResult second = copier.copyChanged(dimensions(), stagingRoot, reloaded);

        assertTrue(second.isEmpty(), "reloaded state must still recognise the file as unchanged");
    }

    @Test
    void nonMcaFilesInTheRegionDirectoryAreIgnored() throws IOException {
        writeRegionFile("r.0.0.mca", "alpha");
        Files.writeString(regionDir.resolve("r.0.0.mca.tmp"), "leftover");
        Files.writeString(regionDir.resolve("README.txt"), "not a region file");

        CopyResult result = copier.copyChanged(dimensions(), stagingRoot, CopyState.empty());

        assertEquals(List.of("world/region/r.0.0.mca"), result.copiedRelativePaths());
    }

    @Test
    void anAbortedCopyLeavesNoTemporaryFileBehind() throws IOException {
        writeRegionFile("r.0.0.mca", "alpha");
        // Make the staging destination directory impossible to create by occupying its path
        // with a regular file -- Files.createDirectories(...) will then fail for every file
        // under it, simulating an I/O failure partway through a batch.
        Files.createDirectories(stagingRoot);
        Files.writeString(stagingRoot.resolve("world"), "blocking file, not a directory");

        assertTrue(
                org.junit.jupiter.api.Assertions.assertThrows(
                                IOException.class, () -> copier.copyChanged(dimensions(), stagingRoot, CopyState.empty()))
                        .getMessage()
                        != null);

        // No stray *.tmp file anywhere under staging -- nothing "half" was left behind.
        try (Stream<Path> walk = Files.exists(stagingRoot) ? Files.walk(stagingRoot) : Stream.empty()) {
            assertFalse(walk.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")));
        }
    }

    private List<DimensionRegionDir> dimensions() {
        return List.of(new DimensionRegionDir("world/region", regionDir));
    }

    private void writeRegionFile(String name, String content) throws IOException {
        Files.writeString(
                regionDir.resolve(name), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Explicitly advances a file's mtime by 5 seconds instead of sleeping the test thread: some
     * filesystems (notably ext4 with a 1s-granularity mount, or overlay filesystems used in CI
     * containers) can otherwise report the same mtime for two writes issued within the same
     * second, making a real sleep both slow and still not fully reliable.
     */
    private static void bumpMtime(Path file) throws IOException {
        FileTime current = Files.getLastModifiedTime(file);
        Files.setLastModifiedTime(file, FileTime.fromMillis(current.toMillis() + 5000));
    }
}
