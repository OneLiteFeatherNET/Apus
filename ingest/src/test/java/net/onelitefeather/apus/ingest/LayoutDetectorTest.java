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
package net.onelitefeather.apus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.onelitefeather.apus.ingest.LayoutDetector.LayoutDetectionException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayoutDetectorTest {

    @Test
    void vanillaLayoutWithAllThreeDimensionsIsRecognizedAndMappedCorrectly(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        createRegionDir(world);
        createRegionDir(world.resolve("DIM-1"));
        createRegionDir(world.resolve("DIM1"));

        WorldLayout layout = LayoutDetector.detect(root, "world", null);

        assertEquals("vanilla", layout.kind());
        assertEquals(world.resolve("region"), layout.dimensions().get("overworld"));
        assertEquals(world.resolve("DIM-1").resolve("region"), layout.dimensions().get("the_nether"));
        assertEquals(world.resolve("DIM1").resolve("region"), layout.dimensions().get("the_end"));
    }

    @Test
    void vanillaLayoutWithOnlyOverworldIsRecognizedSinceMissingNetherAndEndIsNormal(@TempDir Path root)
            throws IOException {
        Path world = root.resolve("world");
        createRegionDir(world);

        WorldLayout layout = LayoutDetector.detect(root, "world", null);

        assertEquals("vanilla", layout.kind());
        assertEquals(world.resolve("region"), layout.dimensions().get("overworld"));
        assertFalse(layout.dimensions().containsKey("the_nether"));
        assertFalse(layout.dimensions().containsKey("the_end"));
    }

    @Test
    void bukkitLayoutWithSiblingWorldFoldersIsRecognizedAndMappedToLogicalDimensionNames(@TempDir Path root)
            throws IOException {
        createRegionDir(root.resolve("world"));
        createRegionDir(root.resolve("world_nether").resolve("DIM-1"));
        createRegionDir(root.resolve("world_the_end").resolve("DIM1"));

        WorldLayout layout = LayoutDetector.detect(root, "world", null);

        assertEquals("bukkit", layout.kind());
        assertEquals(
                root.resolve("world").resolve("region"), layout.dimensions().get("overworld"));
        assertEquals(
                root.resolve("world_nether").resolve("DIM-1").resolve("region"),
                layout.dimensions().get("the_nether"));
        assertEquals(
                root.resolve("world_the_end").resolve("DIM1").resolve("region"),
                layout.dimensions().get("the_end"));
    }

    @Test
    void extraWrappingDirectoryFromAZipUploadIsSeenThrough(@TempDir Path root) throws IOException {
        Path wrapper = root.resolve("upload-a1b2c3");
        Path world = wrapper.resolve("world");
        createRegionDir(world);
        createRegionDir(world.resolve("DIM-1"));

        WorldLayout layout = LayoutDetector.detect(root, "world", null);

        assertEquals("vanilla", layout.kind());
        assertEquals(world.resolve("region"), layout.dimensions().get("overworld"));
        assertEquals(world.resolve("DIM-1").resolve("region"), layout.dimensions().get("the_nether"));
    }

    @Test
    void structureWithoutAnyRegionDirectoryFailsAndMessageNamesTheFoundPaths(@TempDir Path root) throws IOException {
        Path world = root.resolve("world");
        Path playerdata = world.resolve("playerdata");
        Path stats = world.resolve("stats");
        Files.createDirectories(playerdata);
        Files.createDirectories(stats);

        LayoutDetectionException exception =
                assertThrows(LayoutDetectionException.class, () -> LayoutDetector.detect(root, "world", null));

        assertTrue(exception.getMessage().contains(playerdata.toString()), exception.getMessage());
        assertTrue(exception.getMessage().contains(stats.toString()), exception.getMessage());
    }

    @Test
    void forcingBukkitLayoutOnAVanillaStructureFailsInsteadOfSilentlyReturningTheWrongLayout(@TempDir Path root)
            throws IOException {
        Path world = root.resolve("world");
        createRegionDir(world);
        createRegionDir(world.resolve("DIM-1"));
        createRegionDir(world.resolve("DIM1"));

        assertThrows(
                LayoutDetectionException.class, () -> LayoutDetector.detect(root, "world", "bukkit"));
    }

    @Test
    void worldNameContainingDotDotSegmentsFailsInsteadOfEscapingRoot(@TempDir Path root) {
        LayoutDetectionException exception = assertThrows(
                LayoutDetectionException.class, () -> LayoutDetector.detect(root, "../../etc", null));

        assertTrue(exception.getMessage().contains("path separators"), exception.getMessage());
    }

    @Test
    void symlinkEscapingTheWorkingDirectoryIsNotReturnedAsADimensionPath(@TempDir Path root, @TempDir Path outside)
            throws IOException {
        createRegionDir(root.resolve("world"));
        Path secretRegion = outside.resolve("secret").resolve("region");
        Files.createDirectories(secretRegion);
        Path netherLink = root.resolve("world").resolve("DIM-1");
        try {
            Files.createSymbolicLink(netherLink, outside.resolve("secret"));
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic links are not supported on this filesystem: " + e.getMessage());
            return;
        }

        WorldLayout layout = LayoutDetector.detect(root, "world", null);

        assertEquals("vanilla", layout.kind());
        assertEquals(
                root.resolve("world").resolve("region"),
                layout.dimensions().get("overworld"));
        assertFalse(
                layout.dimensions().containsKey("the_nether"),
                "a dimension reached only through a symlink escaping the root must not be reported");
    }

    @Test
    void symlinkInPlaceOfARegionDirectoryIsRejected(@TempDir Path root, @TempDir Path outside) throws IOException {
        Files.createDirectories(root.resolve("world"));
        try {
            Files.createSymbolicLink(root.resolve("world").resolve("region"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("Symbolic links are not supported on this filesystem: " + e.getMessage());
            return;
        }

        assertThrows(LayoutDetectionException.class, () -> LayoutDetector.detect(root, "world", null));
    }

    private static void createRegionDir(Path dimensionDir) throws IOException {
        Files.createDirectories(dimensionDir.resolve("region"));
    }
}
