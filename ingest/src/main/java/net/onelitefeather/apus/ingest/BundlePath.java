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

/**
 * The single place a bundle's location within the destination bucket is computed from its
 * identifying pieces -- {@code tenant}, the {@link net.onelitefeather.apus.ingest.BundleWriter
 * source} that produced it, its {@code worldId}, and (for a specific version) its {@code
 * version}.
 *
 * <p><b>Why {@code sourceName} is part of the path, not just {@code tenant}/{@code worldId}.</b>
 * {@code worldId} is the Minecraft world's own directory name (commonly the vanilla default,
 * {@code "world"}), not something the operator guarantees unique -- two {@code WorldSource}s in
 * the same namespace can both ingest a world literally named {@code world}. Without a
 * source-scoped segment, both sources would write into (and enumerate retention over) the exact
 * same bucket prefix, and one source's retention pass could delete the other's still-referenced
 * bundle. Kubernetes already guarantees resource names are unique within a namespace, so using the
 * owning {@code WorldSource}'s name as a path segment is a free, race-free way to give every
 * source its own prefix.
 *
 * <p>Before this class existed, this exact three-part concatenation was duplicated across {@link
 * BundleWriter}, {@code WorldIngestReconciler}, and {@code AwsBundleStore} (two of them in the
 * {@code operator} module, which depends on this one) -- with no {@code sourceName} segment at
 * all, which is precisely how the two-sources-same-world-name collision above was possible. One
 * shared place for the concatenation makes that class of drift structurally impossible: every
 * caller that needs a bundle path calls here instead of rebuilding it locally.
 */
public final class BundlePath {

    private BundlePath() {}

    /**
     * The prefix every version of one source's one world is written under, ending in {@code "/"}
     * so it can be used directly as an S3 {@code ListObjectsV2} prefix/delimiter query.
     */
    public static String prefix(String tenant, String sourceName, String worldId) {
        return tenant + "/" + sourceName + "/" + worldId + "/";
    }

    /** One specific bundle version's root path within the bucket. */
    public static String of(String tenant, String sourceName, String worldId, String version) {
        return prefix(tenant, sourceName, worldId) + version;
    }
}
