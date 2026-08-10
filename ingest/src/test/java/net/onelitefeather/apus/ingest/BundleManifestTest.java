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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BundleManifestTest {

    private static BundleManifest sampleManifest() {
        return new BundleManifest(
                1,
                "acme",
                "spawn-world",
                "2026-08-08T12-00-00Z",
                new BundleManifest.SourceInfo("s3", "backups/spawn-2026-08-08.zip", "vanilla"),
                "1.21.1",
                List.of(
                        new BundleManifest.DimensionInfo(
                                "overworld",
                                "acme/spawn-world/2026-08-08T12-00-00Z/dimensions/overworld",
                                List.of(new int[] {0, 0}, new int[] {-1, 3}),
                                2),
                        new BundleManifest.DimensionInfo(
                                "the_nether",
                                "acme/spawn-world/2026-08-08T12-00-00Z/dimensions/the_nether",
                                List.of(new int[] {0, 0}),
                                1)),
                12_345_678L,
                new BundleManifest.Checksums("SHA-256", "deadbeef"));
    }

    @Test
    void roundTripThroughJsonIsLossless() {
        BundleManifest original = sampleManifest();

        String json = original.toJson();
        BundleManifest decoded = BundleManifest.fromJson(json);

        // Records containing arrays (List<int[]>) don't get a useful equals() from the compiler
        // (int[] compares by reference), so re-serialising the decoded value and comparing the
        // JSON text proves the round trip is lossless without relying on that broken equality.
        assertEquals(json, decoded.toJson());

        assertEquals(original.schemaVersion(), decoded.schemaVersion());
        assertEquals(original.tenant(), decoded.tenant());
        assertEquals(original.worldId(), decoded.worldId());
        assertEquals(original.version(), decoded.version());
        assertEquals(original.source(), decoded.source());
        assertEquals(original.minecraftVersion(), decoded.minecraftVersion());
        assertEquals(original.sizeBytes(), decoded.sizeBytes());
        assertEquals(original.checksums(), decoded.checksums());

        assertEquals(2, decoded.dimensions().size());
        assertEquals("overworld", decoded.dimensions().get(0).id());
        assertEquals(
                original.dimensions().get(0).path(),
                decoded.dimensions().get(0).path());
        assertEquals(2, decoded.dimensions().get(0).regionCount());
        assertArrayEquals(
                new int[] {0, 0}, decoded.dimensions().get(0).regions().get(0));
        assertArrayEquals(
                new int[] {-1, 3}, decoded.dimensions().get(0).regions().get(1));
        assertArrayEquals(
                new int[] {0, 0}, decoded.dimensions().get(1).regions().get(0));
    }

    @Test
    void jsonContainsHumanReadableFieldNames() {
        String json = sampleManifest().toJson();

        assertTrue(json.contains("\"schemaVersion\""));
        assertTrue(json.contains("\"tenant\""));
        assertTrue(json.contains("\"worldId\""));
        assertTrue(json.contains("\"dimensions\""));
        assertTrue(json.contains("\"regionCount\""));
        assertTrue(json.contains("\"checksums\""));
    }

    @Test
    void nullFieldsRoundTripAsNull() {
        BundleManifest withNulls = new BundleManifest(
                1,
                "acme",
                "spawn-world",
                "v1",
                new BundleManifest.SourceInfo(null, "v1", "vanilla"),
                null,
                List.of(),
                0L,
                new BundleManifest.Checksums("SHA-256", "e3b0c4"));

        BundleManifest decoded = BundleManifest.fromJson(withNulls.toJson());

        assertNull(decoded.minecraftVersion());
        assertNull(decoded.source().type());
        assertTrue(decoded.dimensions().isEmpty());
    }

    @Test
    void stringsWithSpecialCharactersSurviveTheRoundTrip() {
        BundleManifest manifest = new BundleManifest(
                1,
                "acme",
                "world \"quoted\" \\ name\nwith\ttab",
                "v1",
                new BundleManifest.SourceInfo("s3", "ref", "vanilla"),
                null,
                List.of(),
                0L,
                new BundleManifest.Checksums("SHA-256", "e3b0c4"));

        BundleManifest decoded = BundleManifest.fromJson(manifest.toJson());

        assertEquals(manifest.worldId(), decoded.worldId());
    }

    @Test
    void fromJsonRejectsNonObjectRoot() {
        assertThrows(IllegalArgumentException.class, () -> BundleManifest.fromJson("[1,2,3]"));
    }

    @Test
    void fromJsonRejectsTrailingContent() {
        assertThrows(IllegalArgumentException.class, () -> BundleManifest.fromJson("{}{}"));
    }
}
