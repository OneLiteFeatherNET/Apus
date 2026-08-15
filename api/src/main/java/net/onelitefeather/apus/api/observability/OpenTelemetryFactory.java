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
package net.onelitefeather.apus.api.observability;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Factory;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds this service's OpenTelemetry SDK and hands it to the Logback appender {@code
 * logback.xml} declares -- the {@code api} half of {@code docs/logging-and-tracing.md}.
 *
 * <p>Configuration is environment, never code: {@link AutoConfiguredOpenTelemetrySdk} reads the
 * standard {@code OTEL_*} variables, so which collector receives the data is a deployment
 * decision. Nothing here hardcodes an endpoint, a sampler or a protocol.
 *
 * <p><b>Where this deliberately deviates from the contract document, and why.</b>
 * {@code docs/logging-and-tracing.md} states that leaving {@code OTEL_EXPORTER_OTLP_ENDPOINT}
 * unset makes the SDK "a no-op". That is not what the OpenTelemetry Java SDK actually does: with
 * {@code opentelemetry-exporter-otlp} on the classpath (it is, see {@code build.gradle.kts}),
 * {@code otel.traces.exporter}/{@code otel.logs.exporter}/{@code otel.metrics.exporter} all
 * <em>default</em> to {@code otlp}, and the OTLP exporter in turn defaults to
 * {@code http://localhost:4318}. Shipping the documented setup verbatim would therefore have the
 * api pushing spans and log records at a port nothing is listening on, retrying and logging
 * export failures forever -- exactly the "must start exactly as before" property this module is
 * required to keep.
 *
 * <p>{@link #exporterDefaults} closes that gap without changing the documented interface: when
 * <em>neither</em> an OTLP endpoint nor an explicit exporter selection is present in the
 * environment, every exporter is defaulted to {@code none} and the SDK really is inert. Setting
 * {@code OTEL_EXPORTER_OTLP_ENDPOINT} alone enables export, as the contract's table promises;
 * setting {@code OTEL_TRACES_EXPORTER=otlp} alone also enables it, as the OneLiteFeather
 * {@code micronaut-standards:observability} baseline prescribes. The two documents disagree only
 * about which variable is the switch, and this honours both.
 *
 * <p><b>Scope.</b> {@link Context} makes the SDK eager rather than created on first injection, so
 * the appender is wired up while the application context starts instead of whenever the first
 * request happens to reach a bean that needs a {@link Tracer}. Log records emitted before the
 * context starts (Micronaut's own banner, JVM-level warnings) still reach the console appender;
 * they are simply not exported, which is a deliberate, bounded trade-off against calling {@code
 * AutoConfiguredOpenTelemetrySdk} from {@code main} and thereby making the SDK invisible to
 * {@code @MicronautTest}.
 */
@Factory
public class OpenTelemetryFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenTelemetryFactory.class);

    /** Instrumentation scope name for every span this module creates by hand. */
    static final String INSTRUMENTATION_SCOPE = "net.onelitefeather.apus.api";

    /**
     * Falls back to the deployment name design spec §11 uses, so a trace is attributable even
     * when a deployment forgot {@code OTEL_SERVICE_NAME}. Supplied as a <em>default</em>: the
     * environment still wins.
     */
    private static final String DEFAULT_SERVICE_NAME = "apus-api";

    @Singleton
    @Context
    @Bean(preDestroy = "close")
    public OpenTelemetrySdk openTelemetrySdk() {
        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> Map.of("otel.service.name", DEFAULT_SERVICE_NAME))
                .addPropertiesCustomizer(config -> exporterDefaults(config::getString))
                .build()
                .getOpenTelemetrySdk();

        // Wires the appender logback.xml declares to this SDK. Everything logged from here on
        // carries the active trace and span id into OTLP; before this, and whenever the log
        // exporter is "none", the appender is inert.
        OpenTelemetryAppender.install(sdk);

        LOGGER.info("OpenTelemetry SDK initialised");
        return sdk;
    }

    @Singleton
    public Tracer tracer(OpenTelemetrySdk sdk) {
        return sdk.getTracer(INSTRUMENTATION_SCOPE);
    }

    /**
     * Decides whether this process exports anything at all, given what the environment already
     * configured. Pure and package-private so {@code OpenTelemetryFactoryTest} can pin the
     * "nothing configured means nothing exported" guarantee without an SDK, an environment or a
     * Micronaut context.
     *
     * @param configured resolves an {@code otel.*} property to its configured value, or {@code
     *     null} if the environment does not set it -- in production, {@code
     *     ConfigProperties::getString}
     * @return the properties to override; empty when the environment has already made the choice
     */
    static Map<String, String> exporterDefaults(UnaryOperator<String> configured) {
        boolean endpointConfigured = configured.apply("otel.exporter.otlp.endpoint") != null
                || configured.apply("otel.exporter.otlp.traces.endpoint") != null
                || configured.apply("otel.exporter.otlp.logs.endpoint") != null
                || configured.apply("otel.exporter.otlp.metrics.endpoint") != null;
        if (endpointConfigured) {
            return Map.of();
        }

        Map<String, String> overrides = new HashMap<>();
        for (String signal : new String[] {"otel.traces.exporter", "otel.logs.exporter", "otel.metrics.exporter"}) {
            if (configured.apply(signal) == null) {
                overrides.put(signal, "none");
            }
        }
        return Map.copyOf(overrides);
    }
}
