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
 * start against it. Kept in one place so the two tests cannot silently drift apart on
 * bucket names, paths, or credentials.
 */
final class MinioFixtures {

    static final String ACCESS_KEY = "apustest";
    static final String SECRET_KEY = "apustestsecret";
    static final String WORLD_BUCKET = "bundles";
    static final String MAP_BUCKET = "maps";
    static final String WORLD_PATH = "worlds/demo/v1";

    private MinioFixtures() {}

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
        try (GenericContainer<?> seeder = new GenericContainer<>(DockerImageName.parse("minio/mc:latest"))
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
}
