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
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class TelemetryServerTest {

    @Test
    void configDefaultsToPort8099OnAllInterfaces() {
        TelemetryConfig config = TelemetryConfig.fromEnvironment(name -> null);

        assertEquals(8099, config.port());
        assertEquals("0.0.0.0", config.bindAddress());
        assertTrue(config.enabled());
    }

    @Test
    void configReadsOverridesFromEnvironment() {
        Map<String, String> env = Map.of(
                "APUS_TELEMETRY_PORT", "9110",
                "APUS_TELEMETRY_BIND", "127.0.0.1",
                "APUS_TELEMETRY_ENABLED", "false");

        TelemetryConfig config = TelemetryConfig.fromEnvironment(env::get);

        assertEquals(9110, config.port());
        assertEquals("127.0.0.1", config.bindAddress());
        assertFalse(config.enabled());
    }

    @Test
    void configFallsBackToTheDefaultPortOnGarbageInput() {
        TelemetryConfig config =
                TelemetryConfig.fromEnvironment(name -> "APUS_TELEMETRY_PORT".equals(name) ? "not-a-number" : null);

        assertEquals(8099, config.port(), "a bad port must not prevent the addon from starting");
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void servesProgressAsJson() throws Exception {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "overworld", 0.5, 60L, 1, 4, false, "Updating");

        // Port 0 lets the OS pick a free port, so parallel test runs never collide.
        TelemetryConfig config = new TelemetryConfig("127.0.0.1", 0, true);
        try (TelemetryServer server = new TelemetryServer(config, () -> snapshot)) {
            server.start();

            HttpResponse<String> response = get(server.boundPort(), "/progress");

            assertEquals(200, response.statusCode());
            assertEquals("application/json", response.headers().firstValue("content-type").orElse(""));
            assertTrue(response.body().contains("\"state\":\"rendering\""), response.body());
        }
    }

    @Test
    void servesMetricsAsPrometheusText() throws Exception {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "overworld", 0.5, 60L, 1, 4, false, "Updating");

        try (TelemetryServer server = new TelemetryServer(new TelemetryConfig("127.0.0.1", 0, true), () -> snapshot)) {
            server.start();

            HttpResponse<String> response = get(server.boundPort(), "/metrics");

            assertEquals(200, response.statusCode());
            assertEquals(
                    "text/plain; version=0.0.4",
                    response.headers().firstValue("content-type").orElse(""));
            assertTrue(response.body().contains("apus_render_progress_ratio"), response.body());
        }
    }

    @Test
    void servesHealthzEvenWhenTheProbeFails() throws Exception {
        Supplier<ProgressSnapshot> exploding = () -> {
            throw new IllegalStateException("boom");
        };

        try (TelemetryServer server = new TelemetryServer(new TelemetryConfig("127.0.0.1", 0, true), exploding)) {
            server.start();

            assertEquals(200, get(server.boundPort(), "/healthz").statusCode());
            assertEquals(500, get(server.boundPort(), "/progress").statusCode());
        }
    }

    @Test
    void returns404ForUnknownPaths() throws Exception {
        try (TelemetryServer server = new TelemetryServer(
                new TelemetryConfig("127.0.0.1", 0, true), () -> ProgressSnapshot.idle(0, 1))) {
            server.start();

            assertEquals(404, get(server.boundPort(), "/nope").statusCode());
        }
    }

    @Test
    void returns404ForPathsThatOnlySharePrefixWithARoute() throws Exception {
        // HttpServer matches contexts by string prefix, not path segments, so a naive
        // registration of "/progress" would also answer "/progressX" and "/progress/nested".
        try (TelemetryServer server = new TelemetryServer(
                new TelemetryConfig("127.0.0.1", 0, true), () -> ProgressSnapshot.idle(0, 1))) {
            server.start();

            assertEquals(404, get(server.boundPort(), "/progressX").statusCode());
            assertEquals(404, get(server.boundPort(), "/progress/nested").statusCode());
            assertEquals(404, get(server.boundPort(), "/metricsX").statusCode());
            assertEquals(404, get(server.boundPort(), "/metrics/nested").statusCode());
            assertEquals(404, get(server.boundPort(), "/healthzX").statusCode());
            assertEquals(404, get(server.boundPort(), "/healthz/nested").statusCode());
        }
    }

    @Test
    void handlesRequestsOnADaemonThread() throws Exception {
        AtomicBoolean handlerThreadIsDaemon = new AtomicBoolean(false);
        Supplier<ProgressSnapshot> recordingSupplier = () -> {
            handlerThreadIsDaemon.set(Thread.currentThread().isDaemon());
            return ProgressSnapshot.idle(0, 1);
        };

        try (TelemetryServer server =
                new TelemetryServer(new TelemetryConfig("127.0.0.1", 0, true), recordingSupplier)) {
            server.start();

            get(server.boundPort(), "/progress");

            assertTrue(
                    handlerThreadIsDaemon.get(),
                    "the server's request-handling thread must be a daemon thread, "
                            + "otherwise a completed render pod would never let the JVM exit");
        }
    }

    @Test
    void internalDispatcherThreadIsAlsoADaemonThread() throws Exception {
        // The JDK's HttpServer spawns its own internal accept/dispatch thread
        // ("HTTP-Dispatcher") outside of the executor configured via setExecutor().
        // That thread inherits its daemon flag from whichever thread constructs the
        // HttpServer. If start() is called from a non-daemon thread (as BlueMap's main
        // thread is) without special handling, the dispatcher thread ends up non-daemon
        // and blocks the JVM from ever exiting after a render finishes.
        try (TelemetryServer server = new TelemetryServer(
                new TelemetryConfig("127.0.0.1", 0, true), () -> ProgressSnapshot.idle(0, 1))) {
            server.start();

            boolean foundDispatcherThread = false;
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (thread.getName().startsWith("HTTP-Dispatcher")) {
                    foundDispatcherThread = true;
                    assertTrue(
                            thread.isDaemon(),
                            "HttpServer's internal dispatcher thread must be a daemon thread, "
                                    + "otherwise a completed render pod would never let the JVM exit");
                }
            }
            assertTrue(foundDispatcherThread, "expected to find the HttpServer's internal dispatcher thread");
        }
    }

    @Test
    void boundPortThrowsBeforeTheServerIsStarted() {
        try (TelemetryServer server = new TelemetryServer(
                new TelemetryConfig("127.0.0.1", 0, true), () -> ProgressSnapshot.idle(0, 1))) {
            assertThrows(IllegalStateException.class, server::boundPort);
        }
    }
}
