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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Exposes render progress over HTTP.
 *
 * <p>Uses the JDK's built-in {@link HttpServer} on purpose: the addon must not ship any
 * dependency that could clash with BlueMap's own classpath.
 */
public final class TelemetryServer implements AutoCloseable {

    private final TelemetryConfig config;
    private final Supplier<ProgressSnapshot> snapshotSupplier;
    private HttpServer server;

    public TelemetryServer(TelemetryConfig config, Supplier<ProgressSnapshot> snapshotSupplier) {
        this.config = config;
        this.snapshotSupplier = snapshotSupplier;
    }

    public void start() throws IOException {
        // The JDK's HttpServer implementation spawns its own internal accept/dispatch
        // thread (named "HTTP-Dispatcher") outside of the executor set via setExecutor().
        // That thread inherits the daemon flag from whichever thread constructs the
        // HttpServer, NOT from the executor's thread factory. BlueMap calls this addon's
        // run() method from its own main (non-daemon) thread, so without this wrapper the
        // dispatcher thread would be non-daemon and keep the whole JVM alive forever after
        // a render finishes -- the render-only container would never exit.
        //
        // Building the server from inside a short-lived daemon thread makes the internally
        // spawned dispatcher thread daemon too, so it can never block JVM shutdown.
        //
        // The init thread must forward *any* Throwable it catches, not just IOException.
        // Letting an unexpected RuntimeException (or Error) die silently on this thread
        // would leave `server` unset, join() would still return normally, and start()
        // would report success despite the server never having come up.
        Throwable[] failure = new Throwable[1];
        Thread initThread = new Thread(
                () -> {
                    try {
                        server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
                        server.createContext(
                                "/progress",
                                exactPath(
                                        "/progress",
                                        exchange -> respondWith(exchange, "application/json", JsonWriter::toJson)));
                        server.createContext(
                                "/metrics",
                                exactPath(
                                        "/metrics",
                                        exchange -> respondWith(
                                                exchange, "text/plain; version=0.0.4", PrometheusWriter::toPrometheus)));
                        server.createContext(
                                "/healthz", exactPath("/healthz", exchange -> send(exchange, 200, "text/plain", "ok")));
                        server.createContext("/", exchange -> send(exchange, 404, "text/plain", "not found"));
                        // A single daemon thread is plenty: the operator polls once per second.
                        server.setExecutor(Executors.newSingleThreadExecutor(runnable -> {
                            Thread thread = new Thread(runnable, "apus-telemetry");
                            thread.setDaemon(true);
                            return thread;
                        }));
                        server.start();
                    } catch (Throwable t) {
                        failure[0] = t;
                    }
                },
                "apus-telemetry-init");
        initThread.setDaemon(true);
        initThread.start();
        try {
            initThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while starting the telemetry server", e);
        }
        rethrowIfFailed(failure[0]);
    }

    /**
     * Re-throws whatever the init thread caught, preserving its original type where the
     * caller can observe it: an {@link IOException} comes back as one (the signature
     * declares it), a {@link RuntimeException} or {@link Error} comes back unchanged, and
     * anything else (a checked exception {@code start()} never declared) is wrapped so it
     * still surfaces instead of vanishing.
     */
    private static void rethrowIfFailed(Throwable failure) throws IOException {
        if (failure == null) {
            return;
        }
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IOException("failed to start the telemetry server", failure);
    }

    public int boundPort() {
        if (server == null) {
            throw new IllegalStateException("server not started");
        }
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    /**
     * Wraps a handler so it only fires for an exact path match.
     *
     * <p>{@link HttpServer} matches contexts by string prefix, not path segments, so a
     * context registered for {@code /progress} would otherwise also answer requests for
     * {@code /progressX} or {@code /progress/nested}. Every route in this class must
     * reject those instead, per the "everything else is 404" contract.
     */
    private static HttpHandler exactPath(String expectedPath, HttpHandler handler) {
        return exchange -> {
            if (expectedPath.equals(exchange.getRequestURI().getPath())) {
                handler.handle(exchange);
            } else {
                send(exchange, 404, "text/plain", "not found");
            }
        };
    }

    private void respondWith(HttpExchange exchange, String contentType, Formatter formatter) throws IOException {
        String body;
        try {
            body = formatter.format(snapshotSupplier.get());
        } catch (Throwable t) {
            send(exchange, 500, "text/plain", "progress unavailable: " + t.getClass().getSimpleName());
            return;
        }
        send(exchange, 200, contentType, body);
    }

    private static void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @FunctionalInterface
    private interface Formatter {
        String format(ProgressSnapshot snapshot);
    }
}
