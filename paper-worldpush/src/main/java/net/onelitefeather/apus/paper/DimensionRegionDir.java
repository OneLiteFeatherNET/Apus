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

import java.nio.file.Path;

/**
 * One dimension's region-file directory, on disk and as it will appear as a relative key both in
 * the local staging directory and under the S3 staging prefix.
 *
 * @param relativePrefix the path from the world's parent directory to this dimension's {@code
 *     region} folder, using {@code /} separators regardless of platform (e.g. {@code
 *     "world_nether/DIM-1/region"}) -- this is intentionally identical to the on-disk Bukkit
 *     layout {@code world-ingest}'s layout detector already recognises (see the design spec,
 *     §6.2), so nothing needs translating on either end of the staging prefix
 * @param sourceDir the actual directory on this server's disk to read {@code *.mca} files from
 */
public record DimensionRegionDir(String relativePrefix, Path sourceDir) {}
