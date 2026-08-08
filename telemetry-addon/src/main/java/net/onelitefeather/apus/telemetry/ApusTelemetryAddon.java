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

import de.bluecolored.bluemap.api.BlueMapAPI;
import java.util.concurrent.atomic.AtomicReference;
import net.onelitefeather.apus.telemetry.probe.BlueMapRenderManagerAccess;
import net.onelitefeather.apus.telemetry.probe.LogTailRenderManagerAccess;
import net.onelitefeather.apus.telemetry.probe.RenderManagerAccess;
import net.onelitefeather.apus.telemetry.probe.RenderProgressProbe;

/**
 * Entrypoint of the Apus telemetry addon.
 *
 * <p>BlueMap's {@code AddonLoader} instantiates this class and calls {@link #run()} once,
 * early during startup — before the API is ready. We therefore only register a callback
 * here and start serving immediately; until the API fires, {@code /progress} reports
 * {@code starting}.
 *
 * <p>Two independent routes exist to reach BlueMap's render progress; both are behind {@link
 * RenderManagerAccess} so this class only chooses between them, never reimplements them (see
 * {@code runner/README.md#telemetry} for why both are needed and how each was verified):
 *
 * <ol>
 *   <li>{@link BlueMapRenderManagerAccess} — BlueMap's documented addon route via {@code
 *       BlueMapAPIImpl.plugin()}. Richer (queue depth, thread count), but unconditionally
 *       {@code null} when BlueMap runs as the CLI jar, since the CLI never constructs a
 *       {@code Plugin}.
 *   <li>{@link LogTailRenderManagerAccess} — parses BlueMap's own progress log line off
 *       {@code Logger.global}. Works in CLI mode (that's the whole point), but can't report
 *       queue depth or thread count, since no log line carries them.
 * </ol>
 *
 * <p>The log-tail route is registered unconditionally, before the API callback is even wired,
 * so it works whether or not {@code BlueMapAPI.onEnable} ever fires. The API route, when it
 * does become available, is preferred for its richer data. If registering the log-tail route
 * itself fails (e.g. a future BlueMap version removes {@code Logger.global}), {@code
 * /progress} degrades to {@code degraded: true} instead of reporting {@code starting} forever
 * — see {@link #UNAVAILABLE}.
 */
public final class ApusTelemetryAddon implements Runnable {

    /**
     * Stands in for "no render-manager access could be established at all" (neither the API
     * route nor the log-tail fallback). Every method throws, which {@link RenderProgressProbe}
     * — deliberately left untouched by this class — turns into {@code degraded: true} instead
     * of the permanent {@code starting} state a {@code null} {@link RenderManagerAccess} would
     * produce.
     */
    private static final RenderManagerAccess UNAVAILABLE = new RenderManagerAccess() {
        @Override
        public boolean isRunning() {
            throw unavailable();
        }

        @Override
        public int queuedTasks() {
            throw unavailable();
        }

        @Override
        public int renderThreads() {
            throw unavailable();
        }

        @Override
        public TaskInfo currentTask() {
            throw unavailable();
        }

        private IllegalStateException unavailable() {
            return new IllegalStateException(
                    "no BlueMap render-manager access available (neither the API route nor the log-tail fallback"
                            + " could be established)");
        }
    };

    private final AtomicReference<RenderManagerAccess> access = new AtomicReference<>();
    private TelemetryServer server;
    private LogTailRenderManagerAccess logTail;

    @Override
    public void run() {
        TelemetryConfig config = TelemetryConfig.fromEnvironment(System::getenv);
        if (!config.enabled()) {
            System.out.println("[apus-telemetry] disabled via APUS_TELEMETRY_ENABLED=false");
            return;
        }

        RenderProgressProbe probe = new RenderProgressProbe(access::get);
        server = new TelemetryServer(config, probe::sample);

        try {
            server.start();
            System.out.println("[apus-telemetry] listening on " + config.bindAddress() + ":" + server.boundPort());
        } catch (Exception e) {
            // A failed telemetry server must never stop a render from happening.
            System.err.println("[apus-telemetry] failed to start: " + e);
            return;
        }

        // Register the log-tail fallback immediately, independent of whether BlueMapAPI ever
        // fires onEnable -- it's the only route that works at all in CLI mode.
        RenderManagerAccess fallback;
        try {
            LogTailRenderManagerAccess tail = new LogTailRenderManagerAccess();
            tail.register();
            logTail = tail;
            fallback = tail;
        } catch (Throwable t) {
            System.err.println("[apus-telemetry] failed to register log-tail fallback: " + t);
            fallback = UNAVAILABLE;
        }
        access.set(fallback);
        RenderManagerAccess fallbackFinal = fallback;

        BlueMapAPI.onEnable(api -> {
            RenderManagerAccess resolved = BlueMapRenderManagerAccess.createOrNull(api);
            if (resolved != null) {
                access.set(resolved);
            } else {
                System.err.println(
                        "[apus-telemetry] no plugin instance available; falling back to log-tail progress parsing");
                access.set(fallbackFinal);
            }
        });
        // The log-tail fallback keeps working regardless of the API's own lifecycle (it reads
        // BlueMap's logger, not the API), so fall back to it instead of losing progress
        // reporting entirely once the API-backed instance goes away.
        BlueMapAPI.onDisable(api -> access.set(fallbackFinal));

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "apus-telemetry-shutdown"));
    }

    private void stop() {
        if (server != null) {
            server.close();
        }
        if (logTail != null) {
            logTail.unregister();
        }
    }
}
