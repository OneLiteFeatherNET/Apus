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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * The commit point of one version of a world bundle in S3: a versioned, self-describing
 * description of an ingested world.
 *
 * <p>A bundle is considered to exist only once its manifest object has been written -- see
 * {@link BundleWriter}, which writes it strictly after every region file it describes. A reader
 * that finds a manifest can therefore trust that every region file it lists is already present;
 * the absence of a manifest means the bundle version does not exist, regardless of what else may
 * have been left behind by an interrupted write.
 *
 * @param schemaVersion the manifest schema version, bumped whenever the JSON shape changes
 * @param tenant the owning tenant's identifier
 * @param worldId the world's identifier within the tenant
 * @param version this bundle version's identifier
 * @param source where the bundled world data came from
 * @param minecraftVersion the Minecraft version the world was generated/played under, or
 *     {@code null} if not known at bundle time
 * @param dimensions every dimension bundled, and the region files each one contains
 * @param sizeBytes the total size, in bytes, of every region file written for this bundle version
 * @param checksums a content checksum covering all region file bytes written for this bundle
 *     version
 */
public record BundleManifest(
        int schemaVersion,
        String tenant,
        String worldId,
        String version,
        SourceInfo source,
        String minecraftVersion,
        List<DimensionInfo> dimensions,
        long sizeBytes,
        Checksums checksums) {

    // Jackson deserialises Java records out of the box (via their canonical constructor and
    // record-component names, since jackson-databind 2.12 -- no annotations or extra module
    // needed), so the record declarations above double as the JSON schema. Enabling
    // FAIL_ON_TRAILING_TOKENS is the one piece of non-default configuration this class relies
    // on: without it, `readValue` happily accepts and ignores anything after the first JSON
    // value, which would make a manifest that got a stray extra document appended to it decode
    // as if nothing were wrong.
    private static final ObjectMapper MAPPER =
            new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    /**
     * Where the bundled world data came from.
     *
     * @param type the source connector type (e.g. {@code "s3"}, {@code "pterodactyl"}), or
     *     {@code null} if not known to the writer at bundle time
     * @param ref an identifier for the exact source version this bundle was produced from
     * @param detectedLayout the world layout kind (e.g. {@code "vanilla"} or {@code "bukkit"})
     *     that was detected for this world
     */
    public record SourceInfo(String type, String ref, String detectedLayout) {}

    /**
     * One dimension (overworld, the_nether, the_end, ...) inside the bundle, and the region
     * files it contains.
     *
     * @param id the logical dimension name (e.g. {@code "overworld"})
     * @param path the bundle-relative path this dimension's region files were written under
     * @param regions the {@code [x, z]} region coordinates present, read from each region file's
     *     {@code r.<x>.<z>.mca} name
     * @param regionCount {@code regions.size()}, kept alongside the list so consumers doing a
     *     quick count/progress check don't need to materialise it
     */
    public record DimensionInfo(String id, String path, List<int[]> regions, int regionCount) {}

    /**
     * A content checksum for the bundle.
     *
     * @param algorithm the digest algorithm used (e.g. {@code "SHA-256"})
     * @param manifest the hex-encoded digest covering all region file bytes written for this
     *     bundle version
     */
    public record Checksums(String algorithm, String manifest) {}

    /** Serialises this manifest to JSON. */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            // A record made up entirely of the types declared above (primitives, strings,
            // nested records, List<int[]>) cannot fail to serialise; this only exists because
            // the checked exception has to go somewhere.
            throw new IllegalStateException("failed to serialise BundleManifest to JSON", e);
        }
    }

    /**
     * Parses a manifest previously produced by {@link #toJson()}.
     *
     * @throws IllegalArgumentException if {@code json} is not a valid manifest document
     */
    public static BundleManifest fromJson(String json) {
        try {
            return MAPPER.readValue(json, BundleManifest.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid BundleManifest JSON: " + e.getOriginalMessage(), e);
        }
    }
}
