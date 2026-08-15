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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.LoggerContext;
import io.micronaut.context.ApplicationContext;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.slf4j.ILoggerFactory;
import org.slf4j.LoggerFactory;

/**
 * The constraint the whole observability change is allowed exactly zero regressions against: with
 * <b>no OTLP endpoint configured anywhere</b> -- which is the case for this test JVM, for CI, and
 * for any local run -- the application must still start and still serve.
 *
 * <p>Deliberately an {@code @MicronautTest} against the real embedded server rather than a unit
 * test over {@link OpenTelemetryFactory}: the failure mode being guarded against is a startup
 * failure (a missing exporter implementation, an eager {@link io.micronaut.context.annotation
 * .Context}-scoped bean that throws, a Logback configuration Logback refuses to load), and none
 * of those can be reproduced without actually starting a context. Reaching the first assertion
 * at all is therefore already half the test.
 */
@MicronautTest(environments = "apitest")
class ObservabilityStartupTest {

    @Inject
    ApplicationContext context;

    @Inject
    @Client("/")
    HttpClient client;

    @Test
    void theSdkIsBuiltAndTheTracerIsInjectableWithNoOtlpEndpointConfigured() {
        // @Context-scoped, so this bean already existed before the assertion ran -- if building
        // the SDK threw, the context would not have started and no test in this class would run.
        OpenTelemetrySdk sdk = context.getBean(OpenTelemetrySdk.class);
        assertNotNull(sdk);

        Tracer tracer = context.getBean(Tracer.class);
        assertNotNull(tracer);
        // Recording is fine and cheap; what must not happen is a span being *shipped* anywhere,
        // which OpenTelemetryFactoryTest pins at the configuration level.
        assertNotNull(tracer.spanBuilder("startup-probe").startSpan().getSpanContext());
    }

    @Test
    void theApplicationStillServesRequests() {
        // Unauthenticated on purpose: this asserts the server is up and the filter chain is
        // intact, not anything about authorisation (BlueMapMapControllerHttpTest owns that).
        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/api/maps")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void logbackIsTheConfiguredSlf4jBinding() {
        // src/main/resources/logback.xml is the contract's shared file; if a competing binding
        // (or none) ends up on the classpath, every LOGGER in this module goes silent without
        // anything failing. That is exactly the kind of regression nobody notices until an
        // incident, so it is asserted rather than assumed.
        ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        assertInstanceOf(LoggerContext.class, factory);

        LoggerContext loggerContext = (LoggerContext) factory;
        assertNotNull(
                loggerContext.getLogger("net.onelitefeather.apus").getLevel(),
                "logback.xml sets an explicit level for this project's own logger");
    }
}
