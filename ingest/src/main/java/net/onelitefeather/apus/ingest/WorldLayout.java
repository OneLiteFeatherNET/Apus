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

import java.nio.file.Path;
import java.util.Map;

/**
 * The recognized on-disk layout of a Minecraft world, with each present dimension resolved to
 * the region directory that holds its chunk data.
 *
 * <p>Implements {@link BundleWriter.WorldLayoutLike} -- the narrow view {@link BundleWriter} was
 * deliberately written against so it could be built in parallel without depending on this class
 * directly -- so a detected layout can be handed straight to {@link BundleWriter#write} without
 * an adapter.
 *
 * @param kind the recognized layout kind, either {@code "vanilla"} or {@code "bukkit"}
 * @param dimensions logical dimension name ({@code "overworld"}, {@code "the_nether"}, or
 *     {@code "the_end"}) mapped to the resolved path of its region directory; dimensions that do
 *     not exist for this world are simply absent from the map
 */
public record WorldLayout(String kind, Map<String, Path> dimensions) implements BundleWriter.WorldLayoutLike {

    public WorldLayout {
        dimensions = Map.copyOf(dimensions);
    }
}
