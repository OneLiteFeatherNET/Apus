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
package net.onelitefeather.apus.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link HttpPushNotifier} sends exactly the wire shape {@code PushController}/{@code
 * PushReportRequest} (module {@code api}, package {@code net.onelitefeather.apus.api.rest.push})
 * actually expects: the token as a URL path segment (never a header, despite what an earlier
 * version of {@code config.yml}'s comment claimed), and a JSON body with exactly {@code
 * sourceName}/{@code version} -- the two fields {@code PushReportRequest} deserializes. Before
 * this test (and the fix it locks in) existed, this class sent {@code tenant}/{@code
 * worldName}/{@code fileCount}/{@code bytesUploaded} instead, which {@code PushController} would
 * have rejected with a 400 (missing {@code sourceName}/{@code version}) on every real push --
 * exactly the kind of plugin/endpoint drift that only surfaces in production without a test like
 * this one.
 */
class HttpPushNotifierTest {

    private HttpServer server;
    private volatile String capturedPath;
    private volatile String capturedBody;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTheTokenAsAPathSegmentAndSourceNameAndVersionAsTheJsonBody() throws IOException {
        startServer(204);
        HttpPushNotifier notifier = new HttpPushNotifier(baseUrl(), "sh4r3d-t0ken");

        notifier.notifyPushComplete(new PushSummary("survival-source", "2026-08-09T12-00-00Z", "world", 3, 42));

        assertEquals("/api/push/sh4r3d-t0ken", capturedPath, "token must be a path segment, never a header");
        assertEquals("{\"sourceName\":\"survival-source\",\"version\":\"2026-08-09T12-00-00Z\"}", capturedBody);
    }

    @Test
    void aNonTwoXxResponseThrows() throws IOException {
        startServer(400);
        HttpPushNotifier notifier = new HttpPushNotifier(baseUrl(), "token");

        assertThrows(
                HttpPushNotifier.PushNotificationException.class,
                () -> notifier.notifyPushComplete(new PushSummary("source", "v1", "world", 1, 1)));
    }

    @Test
    void anUnreachableApiThrowsWithoutLeakingTheTokenInTheMessage() {
        HttpPushNotifier notifier = new HttpPushNotifier(URI.create("http://127.0.0.1:1"), "super-secret-token");

        HttpPushNotifier.PushNotificationException e = assertThrows(
                HttpPushNotifier.PushNotificationException.class,
                () -> notifier.notifyPushComplete(new PushSummary("source", "v1", "world", 1, 1)));
        assertTrue(
                !e.getMessage().contains("super-secret-token"),
                "the failure message must never echo the push token");
    }

    private void startServer(int statusCode) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/api/push/", exchange -> {
            capturedPath = exchange.getRequestURI().getPath();
            capturedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] response = new byte[0];
            exchange.sendResponseHeaders(statusCode, response.length == 0 ? -1 : response.length);
            exchange.close();
        });
        server.start();
    }

    private URI baseUrl() {
        return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort());
    }
}
