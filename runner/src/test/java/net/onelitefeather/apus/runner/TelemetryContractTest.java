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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The early-warning system for BlueMap upgrades.
 *
 * <p>If BlueMap changes how the internal render manager is reached, every unit test still
 * passes — only this test fails. Run it against every BlueMap version Apus claims to
 * support before releasing.
 *
 * <p>BlueMap's CLI jar unconditionally constructs its internal {@code BlueMapAPIImpl} with a
 * {@code null} {@code Plugin} (confirmed by decompiling {@code BlueMapCLI.renderMaps()}), so
 * the documented addon route ({@code plugin().getRenderManager()}, in
 * {@link net.onelitefeather.apus.telemetry.probe.BlueMapRenderManagerAccess}) never resolves
 * in CLI/render-only mode — the exact mode {@code apus/runner} uses. Progress is read instead
 * via {@link net.onelitefeather.apus.telemetry.probe.LogTailRenderManagerAccess}, which
 * registers on BlueMap's own {@code Logger.global} (a documented extension point; the CLI's
 * own {@code -l}/{@code -b} flags use the same mechanism) and parses the exact progress line
 * BlueMap's CLI already logs itself. See {@code runner/README.md#telemetry} for the full
 * diagnosis of why the API route fails and why the log-tail route works instead.
 */
class TelemetryContractTest {

    @Test
    void progressEndpointReportsARunningRenderWithARealPercentage() throws Exception {
        try (Network network = Network.newNetwork();
                MinIOContainer minio = MinioFixtures.startMinio(network)) {

            MinioFixtures.seedFixtureWorld(network);

            String image = System.getProperty("apus.runner.image", "apus/runner:dev");

            try (GenericContainer<?> runner = MinioFixtures.runnerContainer(network, image)
                    .withExposedPorts(8099)
                    .withEnv("APUS_FORCE_RENDER", "true")
                    .waitingFor(Wait.forLogMessage(".*apus-telemetry] listening.*", 1)
                            .withStartupTimeout(Duration.ofMinutes(5)))) {

                runner.start();

                String base = "http://" + runner.getHost() + ":" + runner.getMappedPort(8099);
                HttpClient client = HttpClient.newHttpClient();

                boolean sawRendering = false;
                boolean sawProgress = false;
                String lastBody = "";

                long deadline = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis();
                while (System.currentTimeMillis() < deadline && runner.isRunning()) {
                    HttpResponse<String> response;
                    try {
                        response = client.send(
                                HttpRequest.newBuilder(URI.create(base + "/progress")).GET().build(),
                                HttpResponse.BodyHandlers.ofString());
                    } catch (IOException e) {
                        // The render-only container exits the moment BlueMap finishes; a request
                        // racing that shutdown gets its connection reset instead of a response.
                        // Treat that like "container stopped" so the loop falls through to the
                        // assertions below and reports the real diagnostic (never observed
                        // rendering/progress) instead of an opaque low-level network exception.
                        break;
                    }
                    lastBody = response.body();

                    if (lastBody.contains("\"degraded\":true")) {
                        fail("telemetry degraded during a real render — the BlueMap access path broke: " + lastBody);
                    }
                    if (lastBody.contains("\"state\":\"rendering\"")) {
                        sawRendering = true;
                        if (!lastBody.contains("\"progress\":-1")) {
                            sawProgress = true;
                            break;
                        }
                    }
                    Thread.sleep(1000);
                }

                assertTrue(sawRendering, "never observed state=rendering; last body: " + lastBody);
                assertTrue(sawProgress, "never observed a real progress value; last body: " + lastBody);
            }
        }
    }
}
