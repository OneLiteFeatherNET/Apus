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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CopyStateTest {

    @TempDir
    Path tmp;

    @Test
    void loadingAMissingFileReturnsEmptyState() {
        CopyState state = CopyState.load(tmp.resolve("does-not-exist.properties"));

        assertEquals(0, state.size());
        assertNull(state.get("world/region/r.0.0.mca"));
    }

    @Test
    void savedStateRoundTripsThroughLoad() throws IOException {
        Path stateFile = tmp.resolve("push-state.properties");
        CopyState original = CopyState.empty();
        original.put("world/region/r.0.0.mca", new RegionFileState(1024, 1_700_000_000_000L, "abc123"));
        original.put("world_nether/DIM-1/region/r.-1.0.mca", new RegionFileState(2048, 1_700_000_001_000L, "def456"));

        original.save(stateFile);
        CopyState reloaded = CopyState.load(stateFile);

        assertEquals(2, reloaded.size());
        assertEquals(new RegionFileState(1024, 1_700_000_000_000L, "abc123"), reloaded.get("world/region/r.0.0.mca"));
        assertEquals(
                new RegionFileState(2048, 1_700_000_001_000L, "def456"),
                reloaded.get("world_nether/DIM-1/region/r.-1.0.mca"));
    }

    @Test
    void aCorruptStateFileFallsBackToEmptyInsteadOfThrowing() throws IOException {
        Path stateFile = tmp.resolve("push-state.properties");
        Files.writeString(stateFile, "world/region/r.0.0.mca=not-a-valid-encoded-state\n");

        CopyState state = CopyState.load(stateFile);

        assertEquals(0, state.size());
    }

    @Test
    void savingLeavesNoTemporaryFileBehind() throws IOException {
        Path stateFile = tmp.resolve("push-state.properties");
        CopyState state = CopyState.empty();
        state.put("world/region/r.0.0.mca", new RegionFileState(1, 2, "checksum"));

        state.save(stateFile);

        List<Path> leftovers;
        try (var walk = Files.list(tmp)) {
            leftovers = walk.filter(p -> p.getFileName().toString().endsWith(".tmp")).toList();
        }
        assertTrue(leftovers.isEmpty());
    }

    @Test
    void savingTwiceReplacesThePreviousContentAtomically() throws IOException {
        Path stateFile = tmp.resolve("push-state.properties");
        CopyState first = CopyState.empty();
        first.put("world/region/r.0.0.mca", new RegionFileState(1, 2, "first"));
        first.save(stateFile);

        CopyState second = CopyState.empty();
        second.put("world/region/r.0.0.mca", new RegionFileState(1, 2, "second"));
        second.save(stateFile);

        CopyState reloaded = CopyState.load(stateFile);
        assertEquals(1, reloaded.size());
        assertEquals("second", reloaded.get("world/region/r.0.0.mca").checksum());
    }
}
