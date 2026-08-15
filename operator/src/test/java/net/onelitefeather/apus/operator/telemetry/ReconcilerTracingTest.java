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
package net.onelitefeather.apus.operator.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import java.util.UUID;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.map.BlueMapMapReconciler;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Asserts that reconciling actually produces the trace {@code docs/logging-and-tracing.md}
 * promises -- one {@code <Kind> reconcile} span carrying the resource's identity, with nested
 * spans only for the steps that can be slow or fail on their own.
 *
 * <p>Not "a tracer was called": the spans are exported into an {@link InMemorySpanExporter} and
 * inspected by name, attribute and parent, because the thing that breaks in production is a span
 * that is missing, misnamed, unparented (so it never joins the trace) or missing the one
 * attribute someone needs to find it.
 *
 * <p>{@link Tracing} is pointed at a test SDK rather than at {@link
 * io.opentelemetry.api.GlobalOpenTelemetry}: the global may only be set once per JVM, which would
 * make this suite depend on class execution order. See {@link Tracing}'s class Javadoc.
 */
@EnableKubernetesMockClient(crud = true)
class ReconcilerTracingTest {

    KubernetesClient client;

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk telemetry;

    @BeforeEach
    void installTestSdk() {
        exporter = InMemorySpanExporter.create();
        telemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                        .build())
                .build();
        Tracing.use(telemetry);
    }

    @AfterEach
    void removeTestSdk() {
        Tracing.use(OpenTelemetry.noop());
        telemetry.close();
    }

    private SpanData span(String name) {
        List<SpanData> spans = exporter.getFinishedSpanItems();
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no span named '" + name + "'; got "
                        + spans.stream().map(SpanData::getName).toList()));
    }

    @Test
    void reconcilingATenantProducesOneRootSpanWithTheTenantOnIt() {
        Tenant tenant = new Tenant();
        tenant.setMetadata(new ObjectMetaBuilder()
                .withName("friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        tenant.getSpec().setDisplayName("friends");
        tenant.getSpec().getStorage().setQuota("500Gi");

        new TenantReconciler(client, OperatorConfig.defaults()).reconcile(tenant, null);

        SpanData root = span("Tenant reconcile");
        assertEquals("friends", root.getAttributes().get(Tracing.TENANT));
        assertFalse(root.getParentSpanContext().isValid(), "the reconcile span is the root of its own trace");

        // The one step of a tenant reconcile that writes to the API server, and the one someone
        // would ask about when provisioning is slow.
        SpanData provisioning = span("provision tenant namespace");
        assertEquals(root.getSpanId(), provisioning.getParentSpanId(), "nested step must hang off the reconcile span");
        assertEquals(root.getTraceId(), provisioning.getTraceId());
        assertEquals("bluemap-friends", provisioning.getAttributes().get(Tracing.K8S_NAMESPACE_NAME));
        assertEquals("friends", provisioning.getAttributes().get(Tracing.TENANT));
    }

    @Test
    void reconcilingAMapProducesANamespacedRootSpanAndTheBucketWaitAsAChild() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName("survival")
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        map.getSpec().getSource().setDimension("minecraft:overworld");

        new BlueMapMapReconciler(client, OperatorConfig.defaults()).reconcile(map, null);

        SpanData root = span("BlueMapMap reconcile");
        assertEquals("survival", root.getAttributes().get(Tracing.MAP));
        assertEquals("bluemap-friends", root.getAttributes().get(Tracing.K8S_NAMESPACE_NAME));

        SpanData bucket = span("await object bucket claim");
        assertEquals(root.getSpanId(), bucket.getParentSpanId());
        assertEquals("survival", bucket.getAttributes().get(Tracing.MAP));
    }

    /**
     * A failing step must be visible as an error on the span, but the description must be the
     * exception's <em>type</em> and nothing else. Messages coming out of an S3 client or an HTTP
     * connector are exactly where a credential or a signed URL would leak into a span attribute
     * (design spec §12) -- so this asserts the message is absent, not merely that a status is set.
     */
    @Test
    void aFailingStepIsMarkedInErrorWithoutLeakingTheExceptionMessage() {
        assertThrows(
                IllegalStateException.class,
                () -> Tracing.step("discover source versions", () -> {
                    throw new IllegalStateException("connect failed for AKIAEXAMPLE:s3cr3t@example.invalid");
                }));

        SpanData failed = span("discover source versions");
        assertEquals(StatusData.error().getStatusCode(), failed.getStatus().getStatusCode());
        assertEquals(IllegalStateException.class.getName(), failed.getStatus().getDescription());
        assertFalse(
                failed.toString().contains("s3cr3t"),
                "the exception message must never reach the span -- it can carry credentials");
        assertTrue(failed.getEvents().isEmpty(), "recordException would attach the same message as an event");
    }
}
