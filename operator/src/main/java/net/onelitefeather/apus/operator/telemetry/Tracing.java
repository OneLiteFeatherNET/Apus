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

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.TracerProvider;
import io.opentelemetry.context.Scope;
import java.util.function.Supplier;

/**
 * The operator's tracing entry point: one span per reconciliation, plus nested spans for the
 * steps that can be slow or fail on their own. See {@code docs/logging-and-tracing.md} for the
 * contract this implements and {@code ApusOperator} for where the SDK behind it is built.
 *
 * <p><b>Why a holder instead of {@code GlobalOpenTelemetry}.</b> The SDK is handed to this class
 * once at startup ({@link #use(OpenTelemetry)}) rather than read back out of {@link
 * io.opentelemetry.api.GlobalOpenTelemetry}. The global is a JVM-wide singleton that may only be
 * set once per process, which makes "reconciling produces the span I expect" impossible to assert
 * in more than one test class without reaching for package-private reset hooks. Nothing in this
 * module reads the global either -- the only other consumer of the SDK, the Logback appender, is
 * handed the same instance explicitly -- so registering it globally would buy nothing and cost
 * testability. Until {@link #use} is called the tracer is a no-op, which is exactly what a unit
 * test that never starts the operator should see.
 *
 * <p><b>Span naming:</b> {@code <Kind> reconcile} for the root, a short verb phrase for each
 * nested step. Names are fixed strings and never carry a resource name -- that belongs in an
 * attribute, where it does not fragment the aggregate view of "how long does a Tenant
 * reconciliation take".
 *
 * <p><b>Never a span per method call.</b> Only work that can genuinely be slow or fail on its own
 * gets a nested span: provisioning a namespace or a Ceph user, waiting on a Rook bucket claim,
 * building and creating a Job, polling a render pod, applying retention against S3, writing the
 * hosting resources. A trace with fifty 0 ms spans hides the one that took nine seconds.
 *
 * <p><b>Exception messages are deliberately not recorded on a span.</b> {@link
 * #status(Span, Throwable)} sets the error status with the exception's <em>class name</em> only,
 * and {@code Span#recordException} is never called. Credentials, tokens and S3 keys must never
 * appear in a span attribute (design spec §12), and an exception raised while talking to an
 * external source -- the one boundary in this operator where a message could embed request
 * details -- is precisely where that rule is easiest to violate by accident. The same defence
 * {@code WorldSourceReconciler} already applies to its conditions is applied here.
 */
public final class Tracing {

    /** Instrumentation scope every span this operator emits is attributed to. */
    public static final String INSTRUMENTATION_SCOPE = "net.onelitefeather.apus.operator";

    /** OpenTelemetry semantic convention: the namespace a resource lives in. */
    public static final AttributeKey<String> K8S_NAMESPACE_NAME = AttributeKey.stringKey("k8s.namespace.name");

    /** OpenTelemetry semantic convention: the name of a pod. */
    public static final AttributeKey<String> K8S_POD_NAME = AttributeKey.stringKey("k8s.pod.name");

    /** OpenTelemetry semantic convention: the name of a job. */
    public static final AttributeKey<String> K8S_JOB_NAME = AttributeKey.stringKey("k8s.job.name");

    /** The {@code Tenant} being reconciled. */
    public static final AttributeKey<String> TENANT = AttributeKey.stringKey("apus.tenant");

    /** The {@code BlueMapMap} being reconciled or referenced. */
    public static final AttributeKey<String> MAP = AttributeKey.stringKey("apus.map");

    /** The {@code BlueMapRender} being reconciled. */
    public static final AttributeKey<String> RENDER = AttributeKey.stringKey("apus.render");

    /** The {@code WorldSource} being reconciled or referenced. */
    public static final AttributeKey<String> SOURCE = AttributeKey.stringKey("apus.source");

    /** {@code WorldSource.spec.type} -- which connector discovery talks to. */
    public static final AttributeKey<String> SOURCE_TYPE = AttributeKey.stringKey("apus.source.type");

    /** The {@code WorldIngest} being reconciled. */
    public static final AttributeKey<String> INGEST = AttributeKey.stringKey("apus.ingest");

    /** The {@code BlueMapHosting} being reconciled. */
    public static final AttributeKey<String> HOSTING = AttributeKey.stringKey("apus.hosting");

    /** The bucket an object bucket claim resolved to. Never the credentials behind it. */
    public static final AttributeKey<String> BUCKET = AttributeKey.stringKey("apus.bucket");

    /** The world a bundle belongs to, for the ingest side of the chain. */
    public static final AttributeKey<String> WORLD = AttributeKey.stringKey("apus.world");

    private static volatile Tracer tracer = TracerProvider.noop().get(INSTRUMENTATION_SCOPE);

    private Tracing() {}

    /**
     * Points every span this operator emits from now on at {@code openTelemetry}. Called once
     * from {@code ApusOperator} right after the SDK is built, and from a test that wants to
     * assert on the spans a reconciliation produced.
     */
    public static void use(OpenTelemetry openTelemetry) {
        tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
    }

    /** The tracer every span goes through -- a no-op one until {@link #use} has been called. */
    public static Tracer tracer() {
        return tracer;
    }

    /**
     * Runs one reconciliation inside a {@code <kind> reconcile} span carrying the resource's name
     * (under {@code nameKey}) and, for a namespaced resource, its namespace.
     *
     * @param kind the CR kind, used verbatim as the first word of the span name
     * @param nameKey the {@code apus.*} attribute the resource's own name is reported under
     * @param resource the resource being reconciled
     * @param body the reconciliation itself
     */
    public static <T> T reconcile(String kind, AttributeKey<String> nameKey, HasMetadata resource, Supplier<T> body) {
        SpanBuilder builder = tracer.spanBuilder(kind + " reconcile");
        ObjectMeta metadata = resource.getMetadata();
        if (metadata != null) {
            if (metadata.getName() != null) {
                builder.setAttribute(nameKey, metadata.getName());
            }
            if (metadata.getNamespace() != null) {
                builder.setAttribute(K8S_NAMESPACE_NAME, metadata.getNamespace());
            }
        }
        return in(builder.startSpan(), body);
    }

    /** Runs one nested step -- a piece of work that can be slow or fail on its own -- in its own span. */
    public static <T> T step(String name, Supplier<T> body) {
        return step(name, Attributes.empty(), body);
    }

    /** Runs one nested step in its own span, carrying {@code attributes}. */
    public static <T> T step(String name, Attributes attributes, Supplier<T> body) {
        return in(tracer.spanBuilder(name).setAllAttributes(attributes).startSpan(), body);
    }

    /**
     * {@link #step(String, Supplier)} for a step that produces no value.
     *
     * <p>Deliberately <em>not</em> an overload of {@code step}: an implicitly-typed lambda is not
     * checked for compatibility while javac picks between applicable overloads, so a {@code
     * step(name, () -> voidCall())} would be reported as an ambiguous reference between the
     * {@link Supplier} and {@link Runnable} forms rather than resolving to the obvious one. A
     * distinct name costs one word at the call site and removes the trap entirely.
     */
    public static void run(String name, Runnable body) {
        run(name, Attributes.empty(), body);
    }

    /** {@link #step(String, Attributes, Supplier)} for a step that produces no value. */
    public static void run(String name, Attributes attributes, Runnable body) {
        step(name, attributes, () -> {
            body.run();
            return null;
        });
    }

    /**
     * Makes {@code span} current for the duration of {@code body}, ends it either way, and marks
     * it as failed if {@code body} threw -- see the class Javadoc on why only the exception's type
     * is recorded.
     */
    private static <T> T in(Span span, Supplier<T> body) {
        try (Scope ignored = span.makeCurrent()) {
            return body.get();
        } catch (RuntimeException | Error e) {
            status(span, e);
            throw e;
        } finally {
            span.end();
        }
    }

    private static void status(Span span, Throwable thrown) {
        span.setStatus(StatusCode.ERROR, thrown.getClass().getName());
    }
}
