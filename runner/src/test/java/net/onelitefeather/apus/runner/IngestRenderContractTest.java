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
package net.onelitefeather.apus.runner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.onelitefeather.apus.ingest.BundleManifest;
import net.onelitefeather.apus.ingest.IngestConfig;
import net.onelitefeather.apus.ingest.IngestMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Proves the whole phase 2b claim end to end: a world in Bukkit layout, run through the real
 * {@link IngestMain} entry point against real MinIO, produces a bundle that the real {@code
 * apus/runner} image (phase 1) can render without knowing anything about where the world data
 * came from -- the one property the whole ingest layer exists to deliver (see the phase 2b plan's
 * "Goal"). {@link RenderEndToEndTest} already proves the render half in isolation by seeding a
 * bundle-shaped fixture directly with {@code mc mirror}; this test instead produces that bundle
 * with the real ingest code path and only then hands it to the same render container, so a
 * mismatch between what {@link IngestMain} writes and what {@code runner/bin/bundle-sync.sh}
 * expects to read would show up here even if it would not show up in either half tested alone.
 *
 * <p>Reuses {@link MinioFixtures}'s MinIO/network/runner-container machinery rather than
 * duplicating it -- see that class's Javadoc.
 *
 * <p>Requires the runner image to be built beforehand:
 * {@code docker build -f runner/Dockerfile -t apus/runner:dev .}
 */
class IngestRenderContractTest {

    private static final String SOURCE_BUCKET = "ingest-sources";
    private static final String SOURCE_PREFIX = "raw/demo/";
    private static final String SOURCE_KEY = "v1.zip";

    private static final String BUNDLE_TENANT = "acme";
    private static final String BUNDLE_WORLD_ID = "spawn";
    private static final String BUNDLE_VERSION = "v1";
    private static final String BUNDLE_PATH = BUNDLE_TENANT + "/" + BUNDLE_WORLD_ID + "/" + BUNDLE_VERSION;

    // What LayoutDetector.detect must normalise a Bukkit-layout source's sibling folders
    // (world, world_nether, world_the_end) to -- the "core of normalisation" the phase 2b plan
    // calls out explicitly: the same three logical names a vanilla source would also produce.
    private static final List<String> LOGICAL_DIMENSIONS = List.of("overworld", "the_nether", "the_end");
    private static final List<String> REGION_FILE_NAMES = List.of("r.0.0.mca", "r.0.1.mca");

    private static final String MAP_PREFIX = "ingest-e2e";
    private static final String RENDERED_TILE_KEY = MAP_PREFIX + "/overworld/tiles/0/x0/z0.prbm.gz";

    private static final Pattern LS_JSON_KEY = Pattern.compile("\"key\":\"([^\"]*)\"");
    private static final Pattern LS_JSON_LAST_MODIFIED = Pattern.compile("\"lastModified\":\"([^\"]*)\"");

    @Test
    void bukkitLayoutWorldIngestedThenRenderedThroughTheRealRunnerImage(@TempDir Path tempDir) throws Exception {
        Path zipFile = tempDir.resolve(SOURCE_KEY);
        buildBukkitLayoutSourceZip(zipFile);

        try (Network network = Network.newNetwork();
                MinIOContainer minio = MinioFixtures.startMinio(network)) {

            seedSourceAndDestinationBuckets(network, zipFile);

            int exitCode = runIngest(minio, tempDir.resolve("work"));
            assertEquals(0, exitCode, "ingest must succeed against the seeded Bukkit-layout source");

            BundleManifest manifest = fetchAndParseManifest(network, tempDir.resolve("manifest-out"));
            assertManifestIsComplete(manifest);
            assertRealBucketListingMatchesTheManifestWithManifestWrittenLast(network);

            String overworldPath = manifest.dimensions().stream()
                    .filter(d -> d.id().equals("overworld"))
                    .findFirst()
                    .orElseThrow()
                    .path();
            String bundleUrl = "s3://" + MinioFixtures.WORLD_BUCKET + "/" + overworldPath;

            renderBundleAndVerifyATileLanded(network, bundleUrl);
        }
    }

    /**
     * Builds one archive object containing a Bukkit-layout world -- {@code world/region},
     * {@code world_nether/DIM-1/region}, {@code world_the_end/DIM1/region} as sibling folders --
     * out of {@code testdata/mini-world}'s real region files, exactly as {@link
     * net.onelitefeather.apus.ingest.connector.S3SourceConnector} requires: one fetchable object
     * per version, unpacked if it is a recognised archive. The fixture itself only has an
     * overworld; the nether/end folders reuse the same two region files, since only the
     * directory names (not their contents) matter for proving layout normalisation.
     */
    private static void buildBukkitLayoutSourceZip(Path zipFile) throws IOException {
        Path region = MinioFixtures.fixture().resolve("region");
        byte[] regionZeroZero = Files.readAllBytes(region.resolve("r.0.0.mca"));
        byte[] regionZeroOne = Files.readAllBytes(region.resolve("r.0.1.mca"));
        byte[] levelDat = Files.readAllBytes(MinioFixtures.fixture().resolve("level.dat"));

        try (OutputStream fileOut = Files.newOutputStream(zipFile);
                ZipOutputStream zip = new ZipOutputStream(fileOut)) {
            writeZipEntry(zip, "world/level.dat", levelDat);
            writeZipEntry(zip, "world/region/r.0.0.mca", regionZeroZero);
            writeZipEntry(zip, "world/region/r.0.1.mca", regionZeroOne);
            writeZipEntry(zip, "world_nether/DIM-1/region/r.0.0.mca", regionZeroZero);
            writeZipEntry(zip, "world_nether/DIM-1/region/r.0.1.mca", regionZeroOne);
            writeZipEntry(zip, "world_the_end/DIM1/region/r.0.0.mca", regionZeroZero);
            writeZipEntry(zip, "world_the_end/DIM1/region/r.0.1.mca", regionZeroOne);
        }
    }

    private static void writeZipEntry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    /** Creates the source/bundle/map buckets and uploads the Bukkit-layout archive as one object. */
    private static void seedSourceAndDestinationBuckets(Network network, Path zipFile) {
        try (GenericContainer<?> seeder = MinioFixtures.mcContainer(
                        network,
                        "mc alias set m http://minio:9000 " + MinioFixtures.ACCESS_KEY + " "
                                + MinioFixtures.SECRET_KEY + " >/dev/null"
                                + " && mc mb --ignore-existing m/" + SOURCE_BUCKET
                                + " && mc mb --ignore-existing m/" + MinioFixtures.WORLD_BUCKET
                                + " && mc mb --ignore-existing m/" + MinioFixtures.MAP_BUCKET
                                + " && mc cp /source/" + SOURCE_KEY + " m/" + SOURCE_BUCKET + "/" + SOURCE_PREFIX
                                + SOURCE_KEY
                                + " && echo SEEDED")
                .withFileSystemBind(zipFile.getParent().toString(), "/source", BindMode.READ_ONLY)
                .waitingFor(Wait.forLogMessage(".*SEEDED.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
            seeder.start();
        }
    }

    /**
     * Drives the real ingest entry point in-process against the MinIO container's host-mapped
     * port -- the same way {@code S3SourceConnectorTest} already talks to MinIO directly rather
     * than through a container, since the code under test here isn't the thing running inside a
     * container (that's {@code runner}, exercised separately below).
     */
    private static int runIngest(MinIOContainer minio, Path workDir) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(IngestConfig.ENV_SOURCE_TYPE, "s3");
        env.put(IngestConfig.ENV_WORLD_NAME, "world");
        env.put(IngestConfig.ENV_LAYOUT, "auto");
        // The connector computes the fetch key as prefix + version id -- see
        // S3SourceConnector.fetch -- so the version id must be relative to the prefix, not the
        // prefixed key itself.
        env.put(IngestConfig.ENV_SOURCE_VERSION, SOURCE_KEY);
        env.put(IngestConfig.ENV_BUNDLE_BUCKET, MinioFixtures.WORLD_BUCKET);
        env.put(IngestConfig.ENV_BUNDLE_TENANT, BUNDLE_TENANT);
        env.put(IngestConfig.ENV_BUNDLE_WORLD_ID, BUNDLE_WORLD_ID);
        env.put(IngestConfig.ENV_BUNDLE_VERSION, BUNDLE_VERSION);
        env.put(IngestConfig.ENV_S3_ENDPOINT, minio.getS3URL());
        env.put(IngestConfig.ENV_S3_ACCESS_KEY, MinioFixtures.ACCESS_KEY);
        env.put(IngestConfig.ENV_S3_SECRET_KEY, MinioFixtures.SECRET_KEY);
        env.put(IngestConfig.ENV_SOURCE_S3_BUCKET, SOURCE_BUCKET);
        env.put(IngestConfig.ENV_SOURCE_S3_PREFIX, SOURCE_PREFIX);
        env.put(IngestConfig.ENV_SOURCE_S3_ENDPOINT, minio.getS3URL());
        env.put(IngestConfig.ENV_SOURCE_S3_ACCESS_KEY, MinioFixtures.ACCESS_KEY);
        env.put(IngestConfig.ENV_SOURCE_S3_SECRET_KEY, MinioFixtures.SECRET_KEY);

        return IngestMain.run(env, workDir);
    }

    private static BundleManifest fetchAndParseManifest(Network network, Path outDir) throws IOException {
        Files.createDirectories(outDir);
        try (GenericContainer<?> fetcher = MinioFixtures.mcContainer(
                        network,
                        "mc alias set m http://minio:9000 " + MinioFixtures.ACCESS_KEY + " "
                                + MinioFixtures.SECRET_KEY + " >/dev/null"
                                + " && mc cat m/" + MinioFixtures.WORLD_BUCKET + "/" + BUNDLE_PATH
                                + "/manifest.json > /out/manifest.json"
                                + " && echo MANIFEST_FETCHED")
                .withFileSystemBind(outDir.toString(), "/out", BindMode.READ_WRITE)
                .waitingFor(Wait.forLogMessage(".*MANIFEST_FETCHED.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
            fetcher.start();
        }
        String json = Files.readString(outDir.resolve("manifest.json"));
        return BundleManifest.fromJson(json);
    }

    /**
     * Every claim task 7 must prove about the manifest itself: it is complete (all three logical
     * dimensions, from a source that only had Bukkit sibling folders -- the normalisation the
     * plan calls the "core of the ETL layer"), and the region list matches what was actually
     * asked to be written (two region files, {@code r.0.0}/{@code r.0.1}, per dimension).
     */
    private static void assertManifestIsComplete(BundleManifest manifest) {
        assertEquals(1, manifest.schemaVersion());
        assertEquals(BUNDLE_TENANT, manifest.tenant());
        assertEquals(BUNDLE_WORLD_ID, manifest.worldId());
        assertEquals(BUNDLE_VERSION, manifest.version());
        assertEquals("s3", manifest.source().type());
        assertEquals(
                "bukkit",
                manifest.source().detectedLayout(),
                "a source with world/world_nether/world_the_end siblings must be detected as bukkit");
        assertTrue(manifest.sizeBytes() > 0);
        assertEquals("SHA-256", manifest.checksums().algorithm());
        assertFalse(manifest.checksums().manifest().isBlank());

        Set<String> dimensionIds = new LinkedHashSet<>();
        for (BundleManifest.DimensionInfo dimension : manifest.dimensions()) {
            dimensionIds.add(dimension.id());
            assertEquals(
                    BUNDLE_PATH + "/dimensions/" + dimension.id(),
                    dimension.path(),
                    "dimension path must follow the bundle layout runner/bin/bundle-sync.sh expects");
            assertEquals(2, dimension.regionCount());
            Set<String> regionCoords = new LinkedHashSet<>();
            for (int[] region : dimension.regions()) {
                regionCoords.add(region[0] + "," + region[1]);
            }
            assertEquals(
                    Set.of("0,0", "0,1"),
                    regionCoords,
                    "region list must match the two .mca files actually present in the source");
        }
        assertEquals(
                new LinkedHashSet<>(LOGICAL_DIMENSIONS),
                dimensionIds,
                "Bukkit sibling folders must normalise to the same logical dimension names a vanilla "
                        + "layout would produce");
    }

    /**
     * Independently cross-checks the manifest's claims against what MinIO actually holds: exactly
     * the expected six region objects plus the manifest itself, no more, no less, and -- read
     * straight from real object timestamps, not from a fake client's call log the way {@code
     * BundleWriterTest} already proves this in isolation -- the manifest is the object with the
     * latest {@code lastModified} of the bundle, i.e. it really was written last against a real
     * S3-compatible store, not merely in a unit test double.
     */
    private static void assertRealBucketListingMatchesTheManifestWithManifestWrittenLast(Network network) {
        Set<String> expectedKeys = new LinkedHashSet<>();
        expectedKeys.add("manifest.json");
        for (String dimension : LOGICAL_DIMENSIONS) {
            for (String regionFile : REGION_FILE_NAMES) {
                expectedKeys.add("dimensions/" + dimension + "/region/" + regionFile);
            }
        }

        String logs;
        try (GenericContainer<?> lister = MinioFixtures.mcContainer(
                        network,
                        "mc alias set m http://minio:9000 " + MinioFixtures.ACCESS_KEY + " "
                                + MinioFixtures.SECRET_KEY + " >/dev/null"
                                + " && mc ls --recursive --json m/" + MinioFixtures.WORLD_BUCKET + "/" + BUNDLE_PATH
                                + "/"
                                + " && echo LS_DONE")
                .waitingFor(Wait.forLogMessage(".*LS_DONE.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
            lister.start();
            logs = lister.getLogs();
        }

        Map<String, Instant> lastModifiedByKey = new LinkedHashMap<>();
        for (String line : logs.split("\\R")) {
            if (!line.startsWith("{")) {
                continue; // not an mc ls --json line (e.g. the LS_DONE marker)
            }
            Matcher keyMatcher = LS_JSON_KEY.matcher(line);
            Matcher lastModifiedMatcher = LS_JSON_LAST_MODIFIED.matcher(line);
            if (!keyMatcher.find() || !lastModifiedMatcher.find()) {
                continue;
            }
            lastModifiedByKey.put(keyMatcher.group(1), Instant.parse(lastModifiedMatcher.group(1)));
        }

        assertEquals(expectedKeys, lastModifiedByKey.keySet(), "bucket must hold exactly the bundle's own objects:\n" + logs);

        Instant manifestWrittenAt = lastModifiedByKey.get("manifest.json");
        for (Map.Entry<String, Instant> entry : lastModifiedByKey.entrySet()) {
            if (entry.getKey().equals("manifest.json")) {
                continue;
            }
            assertFalse(
                    manifestWrittenAt.isBefore(entry.getValue()),
                    "manifest.json (" + manifestWrittenAt + ") must not be older than " + entry.getKey() + " ("
                            + entry.getValue() + ") -- the manifest is the bundle's commit point and must be "
                            + "written last");
        }
    }

    /**
     * The actual proof that the ingest/render contract holds: starts the real {@code apus/runner}
     * image (phase 1) against the bundle {@link IngestMain} just wrote, using exactly the {@code
     * bundleUrl} a {@code BlueMapRender} would carry -- {@code s3://<bucket>/<dimension path>} --
     * and checks a real rendered tile lands in the map bucket, not merely that the render
     * container exited 0.
     */
    private static void renderBundleAndVerifyATileLanded(Network network, String bundleUrl) {
        String image = System.getProperty("apus.runner.image", "apus/runner:dev");

        try (GenericContainer<?> runner = MinioFixtures.runnerContainer(network, image)
                .withEnv("APUS_WORLD_S3_URL", bundleUrl)
                .withEnv("APUS_MAP_PREFIX", MAP_PREFIX)
                .withEnv("APUS_RENDER_THREADS", "2")
                .waitingFor(Wait.forLogMessage(".*starting BlueMap.*", 1).withStartupTimeout(Duration.ofMinutes(5)))) {

            runner.start();

            long deadline = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis();
            while (runner.isRunning() && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
            }

            assertFalse(runner.isRunning(), "render container must exit after rendering, it must not hang");
            Long exitCode = runner.getCurrentContainerInfo().getState().getExitCodeLong();
            assertEquals(
                    0L,
                    exitCode,
                    "BlueMap CLI must exit 0 rendering the bundle the ingest module just wrote; logs:\n"
                            + runner.getLogs());
        }

        try (GenericContainer<?> verifier = MinioFixtures.mcContainer(
                        network,
                        "mc alias set m http://minio:9000 " + MinioFixtures.ACCESS_KEY + " "
                                + MinioFixtures.SECRET_KEY
                                + " && COUNT=$(mc ls --recursive m/" + MinioFixtures.MAP_BUCKET + " | wc -l)"
                                + " && echo OBJECTS=$COUNT"
                                + " ; (mc stat m/" + MinioFixtures.MAP_BUCKET + "/" + RENDERED_TILE_KEY
                                + " >/dev/null 2>&1 && echo TILE_FOUND=yes || echo TILE_FOUND=no)")
                .waitingFor(Wait.forLogMessage(".*TILE_FOUND=.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
            verifier.start();
            String logs = verifier.getLogs();
            assertTrue(logs.contains("OBJECTS="), logs);
            assertFalse(logs.contains("OBJECTS=0"), "map bucket must not be empty after a render:\n" + logs);
            assertTrue(
                    logs.contains("TILE_FOUND=yes"),
                    "expected a real render tile at " + MinioFixtures.MAP_BUCKET + "/" + RENDERED_TILE_KEY
                            + "; logs:\n" + logs);
        }
    }
}
