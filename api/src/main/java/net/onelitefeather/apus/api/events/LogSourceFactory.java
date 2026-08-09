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
package net.onelitefeather.apus.api.events;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.net.URI;

/**
 * Picks the {@link LogSource} implementation once at startup: {@link LokiLogSource} if
 * {@code apus.loki.url} (environment variable {@code APUS_LOKI_URL}) is set, otherwise the
 * {@link KubernetesPodLogSource} fallback.
 *
 * <p><b>The decision and why:</b> design spec §11.1 mandates Loki specifically so the API needs
 * no direct pod access at all, keeping its ServiceAccount's permissions narrower. Whether that is
 * achievable depends entirely on whether a Loki instance is actually reachable from wherever the
 * API runs -- not a given in every environment this module might be deployed into (e.g. a local
 * or CI run without the full cluster observability stack). Rather than hard-failing when Loki
 * is not configured, this falls back to the direct Kubernetes client path (already a dependency
 * of this module for the render watch), accepting the wider ServiceAccount permissions
 * ({@code get}/{@code list} on {@code pods}, {@code get} on {@code pods/log} in tenant
 * namespaces -- see {@link KubernetesPodLogSource}) that the spec's Loki-only design was meant to
 * avoid, as the honest cost of that trade-off.
 *
 * <p>No health probe against Loki is performed -- presence of the URL is treated as "use it",
 * mirroring how {@code APUS_JWT_JWKS_URI}/{@code APUS_JWT_ISSUER} are already handled in {@code
 * application.yml} (task 1): configuration, not runtime connectivity, decides which path this
 * module takes. A genuine connectivity failure at request time surfaces as a stream error, like
 * any other downstream dependency failing.
 */
@Factory
class LogSourceFactory {

    @Singleton
    LogSource logSource(@Value("${apus.loki.url:}") String lokiUrl, KubernetesClient client) {
        return select(lokiUrl, client);
    }

    /** Extracted for {@code LogSourceFactoryTest} to exercise without a Micronaut context. */
    static LogSource select(String lokiUrl, KubernetesClient client) {
        if (lokiUrl != null && !lokiUrl.isBlank()) {
            return new LokiLogSource(URI.create(lokiUrl));
        }
        return new KubernetesPodLogSource(client);
    }
}
