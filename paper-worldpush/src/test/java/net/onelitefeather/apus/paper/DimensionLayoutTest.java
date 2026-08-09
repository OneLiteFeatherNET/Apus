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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DimensionLayoutTest {

    @TempDir
    Path tmp;

    @Test
    void onlyTheOverworldExistsByDefault() throws IOException {
        Files.createDirectories(tmp.resolve("world/region"));

        List<DimensionRegionDir> dims = DimensionLayout.forWorld(tmp, "world");

        assertEquals(1, dims.size());
        assertEquals("world/region", dims.get(0).relativePrefix());
        assertEquals(tmp.resolve("world/region"), dims.get(0).sourceDir());
    }

    @Test
    void netherAndEndAreIncludedWhenPresent() throws IOException {
        Files.createDirectories(tmp.resolve("world/region"));
        Files.createDirectories(tmp.resolve("world_nether/DIM-1/region"));
        Files.createDirectories(tmp.resolve("world_the_end/DIM1/region"));

        List<DimensionRegionDir> dims = DimensionLayout.forWorld(tmp, "world");

        assertEquals(3, dims.size());
        assertTrue(dims.stream().anyMatch(d -> d.relativePrefix().equals("world/region")));
        assertTrue(dims.stream().anyMatch(d -> d.relativePrefix().equals("world_nether/DIM-1/region")));
        assertTrue(dims.stream().anyMatch(d -> d.relativePrefix().equals("world_the_end/DIM1/region")));
    }

    @Test
    void aWorldThatDoesNotExistAtAllYieldsNoDirectories() {
        assertEquals(List.of(), DimensionLayout.forWorld(tmp, "nonexistent"));
    }
}
