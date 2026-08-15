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

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One span covering the whole lifetime of a Server-Sent Events stream -- the second of the two
 * places {@code docs/logging-and-tracing.md} says the {@code api} module should create a span by
 * hand, because an SSE stream is precisely the case where "how long did this take" is <em>not</em>
 * answered by the request span: the request is over in milliseconds, the stream behind it can run
 * for the length of a render.
 *
 * <p>(The document's other candidate, an informer cache warm-up, has no counterpart in this
 * module: {@code Fabric8RenderRepository} opens a per-request {@code Watch} from a known
 * {@code resourceVersion} and never builds an informer cache, so there is nothing to warm up.)
 *
 * <p><b>Ends exactly once.</b> {@code SseSource} can end a stream from either side -- the producer
 * completing or erroring, or the subscriber cancelling when a browser tab closes -- and in one
 * case ({@code events} against an already-terminal render) ends it from inside the wiring
 * callback, before {@code SseSource} has even taken ownership of the cleanup handle. A plain
 * {@code span.end()} in the cleanup handle would therefore leak an unended span on that path, so
 * the guard lives here rather than at each call site.
 *
 * <p><b>No secrets, by construction.</b> The attributes are a namespace, a render name and a
 * render phase -- all cluster metadata the caller already supplied or already sees in the stream.
 * There is no attribute here that could carry a token, a JWT or an S3 key
 * ({@code docs/logging-and-tracing.md}, "Attributes and secrets").
 */
public final class StreamSpan implements AutoCloseable {

    /** Semantic-convention key; {@code apus.*} is used only where no convention exists. */
    private static final String NAMESPACE_ATTRIBUTE = "k8s.namespace.name";

    private static final String STREAM_ATTRIBUTE = "apus.sse.stream";
    private static final String RENDER_ATTRIBUTE = "apus.render";
    private static final String PHASE_ATTRIBUTE = "apus.render.phase";

    private final Span span;
    private final AtomicBoolean ended = new AtomicBoolean();

    private StreamSpan(Span span) {
        this.span = span;
    }

    /**
     * Starts the span for one render stream. Not made current ({@code Scope}) on purpose: the
     * stream outlives the thread that opens it, and a {@code Scope} that is not closed on the
     * same thread it was opened on corrupts the context for whatever runs there next.
     *
     * @param tracer this module's tracer, from {@code OpenTelemetryFactory}
     * @param stream which of the two streams this is -- {@code "events"} or {@code "logs"}
     * @param namespace the tenant namespace the render lives in, already resolved from the
     *     caller's own token
     * @param renderName the render's Kubernetes name
     * @param phase the render's phase at the moment the stream opened, or {@code null} if the
     *     render carries none yet
     */
    public static StreamSpan start(Tracer tracer, String stream, String namespace, String renderName, String phase) {
        SpanBuilder builder = tracer.spanBuilder("render " + stream + " stream")
                .setSpanKind(SpanKind.SERVER)
                .setAttribute(STREAM_ATTRIBUTE, stream)
                .setAttribute(NAMESPACE_ATTRIBUTE, namespace)
                .setAttribute(RENDER_ATTRIBUTE, renderName);
        if (phase != null) {
            builder.setAttribute(PHASE_ATTRIBUTE, phase);
        }
        return new StreamSpan(builder.startSpan());
    }

    /** Ends the span. Repeat calls are no-ops -- see the class Javadoc for why that matters. */
    public void end() {
        if (ended.compareAndSet(false, true)) {
            span.end();
        }
    }

    /** Records {@code throwable} on the span and ends it. A no-op once already ended. */
    public void endWithError(Throwable throwable) {
        if (ended.compareAndSet(false, true)) {
            span.recordException(throwable);
            span.setStatus(StatusCode.ERROR);
            span.end();
        }
    }

    @Override
    public void close() {
        end();
    }
}
