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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BundleWriterTest {

    private static final String BUCKET = "test-bucket";

    /** Records every {@code putObject} call, in the exact order they happened. */
    private static final class LoggingFakeS3Client implements S3Client {
        private final List<String> keysInOrder = new ArrayList<>();
        private final Map<String, byte[]> objects = new LinkedHashMap<>();
        private final String failOnKeyContaining;

        LoggingFakeS3Client() {
            this(null);
        }

        LoggingFakeS3Client(String failOnKeyContaining) {
            this.failOnKeyContaining = failOnKeyContaining;
        }

        @Override
        public void putObject(String bucket, String key, byte[] content) {
            if (failOnKeyContaining != null && key.contains(failOnKeyContaining)) {
                throw new RuntimeException("simulated failure writing " + key);
            }
            keysInOrder.add(key);
            objects.put(key, content);
        }
    }

    private static final class RecordingProgressSink implements BundleWriter.ProgressSink {
        private final List<long[]> updates = new ArrayList<>();

        @Override
        public void update(long bytesDone, long bytesTotal) {
            updates.add(new long[] {bytesDone, bytesTotal});
        }
    }

    private record FakeLayout(String kind, Map<String, Path> dimensions) implements BundleWriter.WorldLayoutLike {}

    private static void writeRegionFile(Path dir, String name, byte[] content) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), content);
    }

    @Test
    void manifestIsWrittenLastAfterEveryRegionFile(@TempDir Path tempDir) throws IOException {
        Path overworld = tempDir.resolve("overworld");
        Path nether = tempDir.resolve("nether");
        writeRegionFile(overworld, "r.0.0.mca", "ow-0-0".getBytes(StandardCharsets.UTF_8));
        writeRegionFile(overworld, "r.-1.3.mca", "ow-neg1-3".getBytes(StandardCharsets.UTF_8));
        writeRegionFile(nether, "r.0.0.mca", "nether-0-0".getBytes(StandardCharsets.UTF_8));

        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put("overworld", overworld);
        dimensions.put("the_nether", nether);
        FakeLayout layout = new FakeLayout("vanilla", dimensions);

        LoggingFakeS3Client fake = new LoggingFakeS3Client();
        BundleWriter writer = new BundleWriter(fake, BUCKET);

        String bundlePath = writer.write("acme", "spawn", "v1", "s3", "1.21.10", layout, null);

        assertEquals("acme/spawn/v1", bundlePath);
        assertEquals(4, fake.keysInOrder.size(), "3 region files + 1 manifest");

        String manifestKey = "acme/spawn/v1/manifest.json";
        assertEquals(manifestKey, fake.keysInOrder.get(fake.keysInOrder.size() - 1), "manifest must be written last");

        // Every other key must be a region file key, and none of them may be the manifest.
        for (int i = 0; i < fake.keysInOrder.size() - 1; i++) {
            String key = fake.keysInOrder.get(i);
            assertTrue(key.contains("/region/r."), "expected a region file key, got: " + key);
            assertFalse(key.equals(manifestKey));
        }
    }

    @Test
    void aFailureWritingARegionFileLeavesNoManifestBehind(@TempDir Path tempDir) throws IOException {
        Path overworld = tempDir.resolve("overworld");
        writeRegionFile(overworld, "r.0.0.mca", "ow-0-0".getBytes(StandardCharsets.UTF_8));
        writeRegionFile(overworld, "r.0.1.mca", "ow-0-1".getBytes(StandardCharsets.UTF_8));

        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put("overworld", overworld);
        FakeLayout layout = new FakeLayout("vanilla", dimensions);

        // Fails on the second region file (r.0.1.mca), simulating a mid-write network error.
        LoggingFakeS3Client fake = new LoggingFakeS3Client("r.0.1.mca");
        BundleWriter writer = new BundleWriter(fake, BUCKET);

        assertThrows(
                RuntimeException.class, () -> writer.write("acme", "spawn", "v1", "s3", "1.21.10", layout, null));

        assertTrue(fake.objects.keySet().stream().noneMatch(key -> key.endsWith("manifest.json")),
                "no manifest may exist after a failed write: " + fake.objects.keySet());
        // The manifest write is never even attempted; write() must fail before reaching it.
        assertEquals(1, fake.keysInOrder.size(), "only the first region file should have been written");
    }

    @Test
    void manifestRegionListMatchesTheActualMcaFilesOnDisk(@TempDir Path tempDir) throws IOException {
        Path overworld = tempDir.resolve("overworld");
        writeRegionFile(overworld, "r.0.0.mca", "a".getBytes(StandardCharsets.UTF_8));
        writeRegionFile(overworld, "r.2.-5.mca", "bb".getBytes(StandardCharsets.UTF_8));
        writeRegionFile(overworld, "r.-3.-3.mca", "ccc".getBytes(StandardCharsets.UTF_8));
        // A non-region file in the same directory must be ignored.
        Files.write(overworld.resolve("session.lock"), "lock".getBytes(StandardCharsets.UTF_8));

        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put("overworld", overworld);
        FakeLayout layout = new FakeLayout("vanilla", dimensions);

        LoggingFakeS3Client fake = new LoggingFakeS3Client();
        BundleWriter writer = new BundleWriter(fake, BUCKET);

        writer.write("acme", "spawn", "v1", "s3", "1.21.10", layout, null);

        byte[] manifestBytes = fake.objects.get("acme/spawn/v1/manifest.json");
        BundleManifest manifest = BundleManifest.fromJson(new String(manifestBytes, StandardCharsets.UTF_8));

        assertEquals(1, manifest.dimensions().size());
        BundleManifest.DimensionInfo overworldInfo = manifest.dimensions().get(0);
        assertEquals("overworld", overworldInfo.id());
        assertEquals(3, overworldInfo.regionCount());
        assertEquals(3, overworldInfo.regions().size());

        List<int[]> regions = overworldInfo.regions();
        assertArrayEquals(new int[] {-3, -3}, regions.get(0));
        assertArrayEquals(new int[] {0, 0}, regions.get(1));
        assertArrayEquals(new int[] {2, -5}, regions.get(2));

        assertEquals(6L, manifest.sizeBytes(), "1 + 2 + 3 bytes across the three region files");
        assertEquals("vanilla", manifest.source().detectedLayout());
    }

    @Test
    void progressIsReportedAsRegionFilesAreWritten(@TempDir Path tempDir) throws IOException {
        Path overworld = tempDir.resolve("overworld");
        writeRegionFile(overworld, "r.0.0.mca", new byte[10]);
        writeRegionFile(overworld, "r.0.1.mca", new byte[20]);

        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put("overworld", overworld);
        FakeLayout layout = new FakeLayout("vanilla", dimensions);

        LoggingFakeS3Client fake = new LoggingFakeS3Client();
        RecordingProgressSink progress = new RecordingProgressSink();
        BundleWriter writer = new BundleWriter(fake, BUCKET);

        writer.write("acme", "spawn", "v1", "s3", "1.21.10", layout, progress);

        assertEquals(2, progress.updates.size());
        long[] first = progress.updates.get(0);
        long[] second = progress.updates.get(1);
        assertEquals(30L, first[1], "total bytes is known upfront from the files on disk");
        assertEquals(30L, second[1]);
        assertEquals(30L, second[0], "final update reports every byte as done");
        assertTrue(first[0] <= second[0]);
    }

    @Test
    void writeToleratesANullProgressSink(@TempDir Path tempDir) throws IOException {
        Path overworld = tempDir.resolve("overworld");
        writeRegionFile(overworld, "r.0.0.mca", "x".getBytes(StandardCharsets.UTF_8));

        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put("overworld", overworld);
        FakeLayout layout = new FakeLayout("vanilla", dimensions);

        BundleWriter writer = new BundleWriter(new LoggingFakeS3Client(), BUCKET);

        String bundlePath = writer.write("acme", "spawn", "v1", "s3", "1.21.10", layout, null);

        assertEquals("acme/spawn/v1", bundlePath);
    }

    @Test
    void manifestRecordsTheSourceTypeAndMinecraftVersionSuppliedByTheCaller(@TempDir Path tempDir)
            throws IOException {
        Path overworld = tempDir.resolve("overworld");
        writeRegionFile(overworld, "r.0.0.mca", "a".getBytes(StandardCharsets.UTF_8));

        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put("overworld", overworld);
        FakeLayout layout = new FakeLayout("bukkit", dimensions);

        LoggingFakeS3Client fake = new LoggingFakeS3Client();
        BundleWriter writer = new BundleWriter(fake, BUCKET);

        writer.write("acme", "spawn", "v1", "pterodactyl", "1.20.4", layout, null);

        byte[] manifestBytes = fake.objects.get("acme/spawn/v1/manifest.json");
        BundleManifest manifest = BundleManifest.fromJson(new String(manifestBytes, StandardCharsets.UTF_8));

        assertEquals("pterodactyl", manifest.source().type());
        assertEquals("bukkit", manifest.source().detectedLayout());
        assertEquals("1.20.4", manifest.minecraftVersion());
    }

    @Test
    void aNullSourceTypeAndMinecraftVersionAreToleratedAndRoundTripAsNull(@TempDir Path tempDir) throws IOException {
        Path overworld = tempDir.resolve("overworld");
        writeRegionFile(overworld, "r.0.0.mca", "a".getBytes(StandardCharsets.UTF_8));

        Map<String, Path> dimensions = new LinkedHashMap<>();
        dimensions.put("overworld", overworld);
        FakeLayout layout = new FakeLayout("vanilla", dimensions);

        LoggingFakeS3Client fake = new LoggingFakeS3Client();
        BundleWriter writer = new BundleWriter(fake, BUCKET);

        writer.write("acme", "spawn", "v1", null, null, layout, null);

        byte[] manifestBytes = fake.objects.get("acme/spawn/v1/manifest.json");
        BundleManifest manifest = BundleManifest.fromJson(new String(manifestBytes, StandardCharsets.UTF_8));

        assertNull(manifest.source().type());
        assertNull(manifest.minecraftVersion());
    }
}
