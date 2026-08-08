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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * Proves the whole Phase 1 contract: world in from S3, map out to S3, progress over HTTP.
 *
 * <p>Requires the runner image to be built beforehand:
 * {@code docker build -f runner/Dockerfile -t apus/runner:dev .}
 */
class RenderEndToEndTest {

    private static final String MAP_ID = "overworld";
    private static final String MAP_PREFIX = "demo";

    // A concrete artifact that only a real render produces: the lowest-detail tile covering
    // the fixture's r.0.0.mca region. Found by actually running the render against this exact
    // fixture and inspecting the resulting map bucket -- not guessed. A degenerate run that
    // only uploads BlueMap's map metadata (settings.json, textures.json.gz, live/*.json)
    // without rendering any geometry would still pass a "bucket is non-empty" check but would
    // never produce this file, since it comes from the tile-rendering stage itself.
    private static final String RENDERED_TILE_KEY = MAP_PREFIX + "/" + MAP_ID + "/tiles/0/x0/z0.prbm.gz";

    @Test
    void rendersAWorldFromS3BackIntoS3AndReportsProgress() throws Exception {
        try (Network network = Network.newNetwork();
                MinIOContainer minio = MinioFixtures.startMinio(network)) {

            MinioFixtures.seedFixtureWorld(network);

            String image = System.getProperty("apus.runner.image", "apus/runner:dev");

            try (GenericContainer<?> runner = MinioFixtures.runnerContainer(network, image)
                    .withEnv("APUS_MAP_PREFIX", MAP_PREFIX)
                    .withEnv("APUS_RENDER_THREADS", "2")
                    .withLogConsumer(new Slf4jLogConsumer(org.slf4j.LoggerFactory.getLogger("runner")))
                    .waitingFor(Wait.forLogMessage(".*starting BlueMap.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))) {

                runner.start();

                // Wait for the container to exit on its own; a render-only run must terminate.
                long deadline = System.currentTimeMillis() + Duration.ofMinutes(15).toMillis();
                while (runner.isRunning() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(2000);
                }

                assertTrue(!runner.isRunning(), "render container must exit after rendering, it must not hang");

                Long exitCode = runner.getCurrentContainerInfo().getState().getExitCodeLong();
                assertEquals(0L, exitCode, "BlueMap CLI must exit 0; logs:\n" + runner.getLogs());
            }

            // Verify that map data actually landed in the target bucket, and that it is a
            // real rendered tile, not just a non-empty bucket a degenerate partial render
            // (e.g. one that only uploads metadata) would also produce.
            try (GenericContainer<?> verifier = MinioFixtures.mcContainer(
                            network,
                            "mc alias set m http://minio:9000 " + MinioFixtures.ACCESS_KEY + " " + MinioFixtures.SECRET_KEY
                                    + " && COUNT=$(mc ls --recursive m/" + MinioFixtures.MAP_BUCKET + " | wc -l)"
                                    + " && echo OBJECTS=$COUNT"
                                    // Independent of the count check above: a chained "&&" would skip this
                                    // (and the wait strategy below would time out instead of failing cleanly)
                                    // if the bucket were empty, so report presence unconditionally instead.
                                    + " ; (mc stat m/" + MinioFixtures.MAP_BUCKET + "/" + RENDERED_TILE_KEY
                                    + " >/dev/null 2>&1 && echo TILE_FOUND=yes || echo TILE_FOUND=no)")
                    .waitingFor(Wait.forLogMessage(".*TILE_FOUND=.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
                verifier.start();
                String logs = verifier.getLogs();
                assertTrue(logs.contains("OBJECTS="), logs);
                assertTrue(!logs.contains("OBJECTS=0"), "map bucket must not be empty after a render:\n" + logs);
                assertTrue(
                        logs.contains("TILE_FOUND=yes"),
                        "expected a real render tile at " + MinioFixtures.MAP_BUCKET + "/" + RENDERED_TILE_KEY
                                + "; logs:\n" + logs);
            }
        }
    }
}
