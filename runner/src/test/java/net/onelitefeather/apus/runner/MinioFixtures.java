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

import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared MinIO/testcontainers setup for the runner's integration tests.
 *
 * <p>Both {@link RenderEndToEndTest} and {@link TelemetryContractTest} need the exact same
 * fixture world seeded into the exact same bucket layout before a runner container can
 * start against it, and both start the {@code apus/runner} image against MinIO with the same
 * required environment variables. Kept in one place so the two tests cannot silently drift
 * apart on bucket names, paths, credentials, or which environment variables the image needs.
 */
final class MinioFixtures {

    // Generated fresh per test run rather than pinned to a fixed literal, so nothing checked
    // into source ever looks like a real credential and no two runs share one. Lengths follow
    // MinIO's own accessKeyMinLen/secretKeyMinLen (3 / 8 characters, see
    // https://github.com/minio/minio/blob/master/internal/auth/credentials.go) with generous
    // headroom so the container always accepts them.
    static final String ACCESS_KEY = randomAlphanumeric(20);
    static final String SECRET_KEY = randomAlphanumeric(40);
    static final String WORLD_BUCKET = "bundles";
    static final String MAP_BUCKET = "maps";
    static final String WORLD_PATH = "worlds/demo/v1";

    // Pinned instead of "latest": the mc image is used to seed/verify fixtures across both
    // integration tests, and an unpinned "latest" could change behavior under us without
    // warning. Same release as runner/Dockerfile's mc binary, so both stay in lockstep.
    private static final DockerImageName MC_IMAGE = DockerImageName.parse("minio/mc:RELEASE.2025-08-13T08-35-41Z");

    private MinioFixtures() {}

    /** Generates a random alphanumeric string of {@code length} characters. */
    private static String randomAlphanumeric(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return value.toString();
    }

    static Path fixture() {
        return Path.of(System.getProperty("user.dir")).getParent().resolve("testdata/mini-world");
    }

    /** Starts a MinIO container reachable from other containers on {@code network} as "minio". */
    static MinIOContainer startMinio(Network network) {
        MinIOContainer minio = new MinIOContainer(DockerImageName.parse("minio/minio:RELEASE.2024-11-07T00-52-20Z"))
                .withUserName(ACCESS_KEY)
                .withPassword(SECRET_KEY)
                .withNetwork(network)
                .withNetworkAliases("minio");
        minio.start();
        return minio;
    }

    /**
     * Creates the {@code bundles}/{@code maps} buckets and mirrors the fixture world into
     * {@code bundles/worlds/demo/v1}, using a throwaway {@code minio/mc} container.
     */
    static void seedFixtureWorld(Network network) {
        try (GenericContainer<?> seeder = new GenericContainer<>(MC_IMAGE)
                .withNetwork(network)
                .withFileSystemBind(fixture().toString(), "/fixture", BindMode.READ_ONLY)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                .withCommand(
                        "-c",
                        "mc alias set m http://minio:9000 " + ACCESS_KEY + " " + SECRET_KEY
                                + " && mc mb --ignore-existing m/" + WORLD_BUCKET
                                + " && mc mb --ignore-existing m/" + MAP_BUCKET
                                + " && mc mirror /fixture m/" + WORLD_BUCKET + "/" + WORLD_PATH
                                + " && echo SEEDED")
                .waitingFor(Wait.forLogMessage(".*SEEDED.*", 1).withStartupTimeout(Duration.ofMinutes(3)))) {
            seeder.start();
        }
    }

    /** Runs {@code command} in a throwaway {@code minio/mc} container on {@code network}. */
    static GenericContainer<?> mcContainer(Network network, String command) {
        return new GenericContainer<>(MC_IMAGE)
                .withNetwork(network)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("/bin/sh"))
                .withCommand("-c", command);
    }

    /**
     * Builds the {@code apus/runner} container, pre-populated with every environment variable
     * the image needs to render {@code testdata/mini-world} against the MinIO fixture started
     * by {@link #startMinio(Network)}/{@link #seedFixtureWorld(Network)}.
     *
     * <p>The single mandatory difference between callers -- {@code APUS_MAP_PREFIX} controls
     * where in the map bucket the output lands, and callers that don't set one keep BlueMap's
     * bucket-root default -- is left to the caller via {@link GenericContainer#withEnv}, which
     * overrides values set here since it's called after this method returns.
     */
    static GenericContainer<?> runnerContainer(Network network, String image) {
        return new GenericContainer<>(DockerImageName.parse(image))
                .withNetwork(network)
                .withEnv("APUS_MAP_ID", "overworld")
                .withEnv("APUS_DIMENSION", "minecraft:overworld")
                .withEnv("APUS_MC_VERSION", "1.21.10")
                .withEnv("APUS_WORLD_S3_URL", "s3://" + WORLD_BUCKET + "/" + WORLD_PATH)
                .withEnv("APUS_MAP_BUCKET", MAP_BUCKET)
                .withEnv("APUS_S3_ENDPOINT", "http://minio:9000")
                .withEnv("APUS_S3" + "_ACCESS_KEY", ACCESS_KEY)
                .withEnv("APUS_S3" + "_SECRET_KEY", SECRET_KEY);
    }
}
