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
import net.onelitefeather.apus.telemetry.probe.RenderManagerAccess;
import net.onelitefeather.apus.telemetry.probe.RenderProgressProbe;

/**
 * Entrypoint of the Apus telemetry addon.
 *
 * <p>BlueMap's {@code AddonLoader} instantiates this class and calls {@link #run()} once,
 * early during startup — before the API is ready. We therefore only register a callback
 * here and start serving immediately; until the API fires, {@code /progress} reports
 * {@code starting}.
 */
public final class ApusTelemetryAddon implements Runnable {

    private final AtomicReference<RenderManagerAccess> access = new AtomicReference<>();
    private TelemetryServer server;

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

        BlueMapAPI.onEnable(api -> {
            RenderManagerAccess resolved = BlueMapRenderManagerAccess.createOrNull(api);
            access.set(resolved);
            if (resolved == null) {
                System.err.println("[apus-telemetry] no plugin instance available; progress will report as unknown");
            }
        });
        BlueMapAPI.onDisable(api -> access.set(null));

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "apus-telemetry-shutdown"));
    }

    private void stop() {
        if (server != null) {
            server.close();
        }
    }
}
