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
package net.onelitefeather.apus.operator;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.javaoperatorsdk.operator.Operator;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import net.onelitefeather.apus.operator.hosting.BlueMapHostingReconciler;
import net.onelitefeather.apus.operator.ingest.WorldIngestReconciler;
import net.onelitefeather.apus.operator.ingest.WorldSourceReconciler;
import net.onelitefeather.apus.operator.map.BlueMapMapReconciler;
import net.onelitefeather.apus.operator.render.BlueMapRenderReconciler;
import net.onelitefeather.apus.operator.telemetry.Tracing;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The operator's process entry point: builds a Kubernetes client and {@link OperatorConfig} from
 * the environment, registers the six reconcilers against a single {@link Operator} instance,
 * and starts it.
 *
 * <p>There is no Micronaut (or any other framework) integration here on purpose -- the Java
 * Operator SDK has none to offer, and pulling in a dependency injection framework just to call a
 * handful of constructors would not carry its own weight. This class is the whole wiring.
 *
 * <p><b>Staying up:</b> {@link Operator#start()} starts the controllers on background threads and
 * returns, so {@link #main} must not return with it -- the JVM would find no non-daemon thread
 * left, exit 0, and be restarted by Kubernetes about once a minute. {@link #main} therefore parks
 * on a {@link CountDownLatch} that the shutdown hook below releases.
 *
 * <p><b>Shutdown:</b> a JVM shutdown hook stops the {@link Operator} (deregistering its watches)
 * and closes the {@link KubernetesClient} (releasing its HTTP connections) before the process
 * exits. Without it, a {@code SIGTERM} during a rolling deploy would simply kill the process and
 * leave its watches registered against the API server's connection tracking until they time out
 * on their own, which is exactly the kind of thing that slows down the next rollout. Releasing the
 * latch is part of that hook, so a {@code kubectl delete pod} ends the process promptly instead of
 * sitting out the termination grace period and being {@code SIGKILL}ed.
 *
 * <p><b>Startup failure:</b> a cluster connection problem, or any other error surfacing while
 * registering reconcilers or starting the operator, is logged at {@code error} and ends the
 * process with a non-zero exit code -- never silently.
 *
 * <p><b>Observability:</b> {@link #initTelemetry()} builds the OpenTelemetry SDK from the
 * environment and hands it both to the Logback OTLP appender and to {@link Tracing}, before the
 * first log line is written. With no {@code OTEL_EXPORTER_OTLP_ENDPOINT} set -- how every
 * developer machine and every test runs -- no exporter is attached at all (see {@link
 * #exporterDefaults}), so both are inert and startup behaves exactly as it did before. See
 * {@code docs/logging-and-tracing.md}.
 */
public final class ApusOperator {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApusOperator.class);

    private ApusOperator() {}

    public static void main(String[] args) {
        OpenTelemetrySdk telemetry = initTelemetry();
        OperatorConfig config = OperatorConfig.fromEnvironment(System::getenv);

        KubernetesClient client;
        try {
            client = new KubernetesClientBuilder().build();
        } catch (RuntimeException e) {
            LOGGER.error("failed to build a Kubernetes client", e);
            telemetry.close();
            System.exit(1);
            return;
        }

        try {
            run(client, config, telemetry, new CountDownLatch(1), Runtime.getRuntime()::addShutdownHook);
        } catch (RuntimeException e) {
            LOGGER.error("failed to start", e);
            telemetry.close();
            System.exit(1);
        }
    }

    /**
     * Builds the OpenTelemetry SDK from the standard {@code OTEL_*} environment variables and
     * wires it into the two things that consume it: the Logback appender that ships log records
     * over OTLP, and {@link Tracing}, through which every span in this module is created.
     *
     * <p>Called before the first log line on purpose -- the appender buffers only a small number
     * of records emitted before {@code install()}, and anything beyond that would simply never
     * reach the collector.
     *
     * <p>Autoconfiguration's own JVM shutdown hook is disabled ({@code disableShutdownHook()}):
     * this process already registers exactly one hook, in {@link #run}, and folding the SDK's
     * {@code close()} into that same path keeps flush ordering explicit -- the operator is
     * stopped and the client is closed <em>before</em> the exporters are shut down, so the log
     * records and spans produced while shutting down are still exported rather than dropped by an
     * exporter that a second, independently-ordered hook had already closed.
     *
     * <p>The result is deliberately not registered as {@link
     * io.opentelemetry.api.GlobalOpenTelemetry} -- see {@link Tracing}'s class Javadoc for why.
     */
    static OpenTelemetrySdk initTelemetry() {
        return initTelemetry(System::getenv);
    }

    /** {@link #initTelemetry()} against a supplied environment, so a test does not have to mutate the real one. */
    static OpenTelemetrySdk initTelemetry(Function<String, String> env) {
        OpenTelemetrySdk telemetry = AutoConfiguredOpenTelemetrySdk.builder()
                .disableShutdownHook()
                .addPropertiesSupplier(() -> exporterDefaults(env))
                .build()
                .getOpenTelemetrySdk();
        OpenTelemetryAppender.install(telemetry);
        Tracing.use(telemetry);
        return telemetry;
    }

    /**
     * Makes "no {@code OTEL_EXPORTER_OTLP_ENDPOINT} set means the SDK is a no-op" ({@code
     * docs/logging-and-tracing.md}) actually true.
     *
     * <p>Autoconfiguration on its own does <b>not</b> behave that way: with no endpoint
     * configured it still defaults every signal's exporter to {@code otlp} and points it at
     * {@code localhost:4317}, so a developer machine or a test run -- neither of which has a
     * collector -- would spend the process's lifetime retrying a connection that can never
     * succeed and logging the failures. Defaulting the three exporters to {@code none} instead,
     * <em>only</em> while no endpoint is configured, removes that entirely.
     *
     * <p>This does not move configuration into code: values from {@code addPropertiesSupplier}
     * are the lowest-precedence layer autoconfiguration knows, so a deployment that sets {@code
     * OTEL_TRACES_EXPORTER} or {@code OTEL_LOGS_EXPORTER} explicitly still wins, and setting an
     * endpoint at all switches the defaults back off.
     */
    static Map<String, String> exporterDefaults(Function<String, String> env) {
        if (endpointConfigured(env)) {
            return Map.of();
        }
        return Map.of(
                "otel.traces.exporter", "none",
                "otel.logs.exporter", "none",
                "otel.metrics.exporter", "none");
    }

    /** Whether any OTLP endpoint -- the shared one or a per-signal override -- is configured. */
    private static boolean endpointConfigured(Function<String, String> env) {
        return Stream.of(
                        "OTEL_EXPORTER_OTLP_ENDPOINT",
                        "OTEL_EXPORTER_OTLP_TRACES_ENDPOINT",
                        "OTEL_EXPORTER_OTLP_LOGS_ENDPOINT",
                        "OTEL_EXPORTER_OTLP_METRICS_ENDPOINT")
                .map(env)
                .anyMatch(value -> value != null && !value.isBlank());
    }

    /**
     * Registers the reconcilers, starts {@code operator} and then blocks until {@code
     * shutdownSignal} is released by the shutdown hook this method registers through {@code
     * shutdownHookRegistrar}.
     *
     * <p>Extracted from {@link #main} so a test can drive the whole run -- start, block, shut
     * down -- against a mock {@link KubernetesClient} and a hook it triggers itself, instead of a
     * real cluster and a real {@code SIGTERM}.
     *
     * <p>{@code telemetry} is closed by the same shutdown hook that stops the operator, rather
     * than by a second hook of its own -- see {@link #initTelemetry()}.
     */
    static void run(
            KubernetesClient client,
            OperatorConfig config,
            OpenTelemetrySdk telemetry,
            CountDownLatch shutdownSignal,
            Consumer<Thread> shutdownHookRegistrar) {
        Operator operator = new Operator(o -> o.withKubernetesClient(client));
        shutdownHookRegistrar.accept(
                new Thread(() -> shutdown(operator, client, telemetry, shutdownSignal), "apus-operator-shutdown"));

        registerReconcilers(operator, client, config);
        operator.start();

        LOGGER.info("started, watching Tenant/BlueMapMap/BlueMapRender/WorldSource/WorldIngest/BlueMapHosting"
                + " resources");

        awaitShutdown(shutdownSignal);
    }

    /**
     * Blocks until {@code shutdownSignal} is released, i.e. until the shutdown hook has run.
     *
     * <p>The Java Operator SDK 5.5.1 has nothing to offer here: {@link Operator#start()} is
     * documented as "finishes the operator startup process" and returns as soon as the controller
     * and leader-election managers are up, leaving the actual work to executor-service threads,
     * and the only lifecycle helper next to it, {@link Operator#installShutdownHook()}, merely
     * registers a {@code stop()} hook -- it does not block either. There is no {@code run()},
     * {@code join()} or {@code awaitTermination()} on the class. Hence this latch.
     *
     * <p>An interrupt is treated as a shutdown request rather than swallowed: the flag is restored
     * and the method returns, so {@code main} unwinds and the JVM's own shutdown sequence (and
     * with it the hook) takes over.
     */
    private static void awaitShutdown(CountDownLatch shutdownSignal) {
        try {
            shutdownSignal.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Registers all six reconcilers on {@code operator}. Extracted from {@link #main} so a
     * test can exercise the wiring itself -- that every reconciler this operator ships is
     * actually registered -- against a mock {@link KubernetesClient} instead of a real cluster.
     */
    static void registerReconcilers(Operator operator, KubernetesClient client, OperatorConfig config) {
        operator.register(new TenantReconciler(client, config));
        operator.register(new BlueMapMapReconciler(client, config));
        operator.register(new BlueMapRenderReconciler(client, config));
        operator.register(new WorldSourceReconciler(client));
        operator.register(new WorldIngestReconciler(client, config));
        operator.register(new BlueMapHostingReconciler(client, config));
    }

    /**
     * Stops {@code operator}, closes {@code client} and shuts the OpenTelemetry SDK down, in that
     * order, swallowing (but logging) any failure from {@code stop()} so the client is still
     * closed even if stopping the controllers did not go cleanly.
     *
     * <p>The SDK goes last deliberately: every line above may still emit a log record, and
     * flushing the exporters before those records exist would drop exactly the output someone
     * investigating a bad shutdown would want.
     */
    private static void shutdown(
            Operator operator, KubernetesClient client, OpenTelemetrySdk telemetry, CountDownLatch shutdownSignal) {
        LOGGER.info("shutting down");
        try {
            operator.stop();
        } catch (RuntimeException e) {
            LOGGER.error("error while stopping the operator", e);
        } finally {
            client.close();
            telemetry.close();
            shutdownSignal.countDown();
        }
    }
}
