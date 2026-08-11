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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Locates the region-file directories for a world in the on-disk layout a Paper/Bukkit server
 * actually uses: the primary world folder plus its {@code _nether}/{@code _the_end} siblings,
 * each holding a {@code region} (or {@code DIM-1/region}, {@code DIM1/region}) folder.
 *
 * <p>This is deliberately the same {@code bukkit} layout {@code world-ingest}'s layout detector
 * recognises server-side (design spec §6.2) -- picked so the staged copy needs no translation,
 * not because this plugin re-implements that detector. A world that never generated the nether
 * or the end simply has no matching directory; such dimensions are silently omitted rather than
 * treated as an error.
 */
public final class DimensionLayout {

    private DimensionLayout() {}

    /**
     * Returns the region directories that currently exist on disk for {@code worldName}, rooted
     * at {@code serverRoot} (the directory Bukkit world folders live in -- typically the server's
     * working directory).
     */
    public static List<DimensionRegionDir> forWorld(Path serverRoot, String worldName) {
        List<DimensionRegionDir> candidates = List.of(
                new DimensionRegionDir(worldName + "/region", serverRoot.resolve(worldName).resolve("region")),
                new DimensionRegionDir(
                        worldName + "_nether/DIM-1/region",
                        serverRoot.resolve(worldName + "_nether").resolve("DIM-1").resolve("region")),
                new DimensionRegionDir(
                        worldName + "_the_end/DIM1/region",
                        serverRoot.resolve(worldName + "_the_end").resolve("DIM1").resolve("region")));

        List<DimensionRegionDir> existing = new ArrayList<>();
        for (DimensionRegionDir candidate : candidates) {
            if (Files.isDirectory(candidate.sourceDir())) {
                existing.add(candidate);
            }
        }
        return existing;
    }
}
