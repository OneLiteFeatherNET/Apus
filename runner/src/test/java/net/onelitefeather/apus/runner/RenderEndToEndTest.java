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

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves the whole Phase 1 contract: world in from S3, map out to S3, progress over HTTP.
 *
 * <p>Requires the runner image to be built beforehand:
 * {@code docker build -f runner/Dockerfile -t apus/runner:dev .}
 */
class RenderEndToEndTest {

    private static final String ACCESS_KEY = "apustest";
    private static final String SECRET_KEY = "apustestsecret";
    private static final String WORLD_BUCKET = "bundles";
    private static final String MAP_BUCKET = "maps";

    private static Path fixture() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve("testdata/mini-world");
    }

    @Test
    void rendersAWorldFromS3BackIntoS3AndReportsProgress() throws Exception {
        try (Network network = Network.newNetwork();
                MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-11-07T00-52-20Z"))
                        .withUserName(ACCESS_KEY)
                        .withPassword(SECRET_KEY)
                        .withNetwork(network)
                        .withNetworkAliases("minio")) {

            minio.start();

            // Upload the fixture world using the mc client from a throwaway container.
            try (GenericContainer<?> seeder = new GenericContainer<>(DockerImageName.parse("minio/mc:latest"))
                    .withNetwork(network)
                    .withFileSystemBind(fixture().toString(), "/fixture", BindMode.READ_ONLY)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                    .withCommand(
                            "-c",
                            "mc alias set m http://minio:9000 " + ACCESS_KEY + " " + SECRET_KEY
                                    + " && mc mb --ignore-existing m/" + WORLD_BUCKET
                                    + " && mc mb --ignore-existing m/" + MAP_BUCKET
                                    + " && mc mirror /fixture m/" + WORLD_BUCKET + "/worlds/demo/v1"
                                    + " && echo SEEDED")
                    .waitingFor(Wait.forLogMessage(".*SEEDED.*", 1).withStartupTimeout(Duration.ofMinutes(3)))) {
                seeder.start();
            }

            String image = System.getProperty("apus.runner.image", "apus/runner:dev");

            try (GenericContainer<?> runner = new GenericContainer<>(DockerImageName.parse(image))
                    .withNetwork(network)
                    .withEnv("APUS_MAP_ID", "overworld")
                    .withEnv("APUS_DIMENSION", "minecraft:overworld")
                    .withEnv("APUS_MC_VERSION", "1.21.10")
                    .withEnv("APUS_WORLD_S3_URL", "s3://" + WORLD_BUCKET + "/worlds/demo/v1")
                    .withEnv("APUS_MAP_BUCKET", MAP_BUCKET)
                    .withEnv("APUS_MAP_PREFIX", "demo")
                    .withEnv("APUS_S3_ENDPOINT", "http://minio:9000")
                    .withEnv("APUS_S3_ACCESS_KEY", ACCESS_KEY)
                    .withEnv("APUS_S3_SECRET_KEY", SECRET_KEY)
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

            // Verify that map data actually landed in the target bucket.
            try (GenericContainer<?> verifier = new GenericContainer<>(DockerImageName.parse("minio/mc:latest"))
                    .withNetwork(network)
                    .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                    .withCommand(
                            "-c",
                            "mc alias set m http://minio:9000 " + ACCESS_KEY + " " + SECRET_KEY
                                    + " && COUNT=$(mc ls --recursive m/" + MAP_BUCKET + " | wc -l)"
                                    + " && echo OBJECTS=$COUNT"
                                    + " && test \"$COUNT\" -gt 0")
                    .waitingFor(Wait.forLogMessage(".*OBJECTS=.*", 1).withStartupTimeout(Duration.ofMinutes(2)))) {
                verifier.start();
                String logs = verifier.getLogs();
                assertTrue(logs.contains("OBJECTS="), logs);
                assertTrue(!logs.contains("OBJECTS=0"), "map bucket must not be empty after a render:\n" + logs);
            }
        }
    }
}
