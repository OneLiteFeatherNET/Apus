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

import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.sse.Event;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import org.reactivestreams.Publisher;

/**
 * {@code GET /api/renders/{id}/events} and {@code GET /api/renders/{id}/logs} -- live progress
 * and log line SSE streams for one render.
 *
 * <p><b>Tenant check before any stream opens (design spec §10.3, binding for this task):</b>
 * {@link #requireRender} resolves the caller's own namespace via {@link TenantResolver} and looks
 * the render up <em>only</em> there -- never elsewhere. A render that exists in a different
 * tenant's namespace is indistinguishable from one that does not exist at all: both are a plain
 * 404, thrown synchronously before either endpoint constructs a {@link SseSource}, opens a
 * Kubernetes watch, or starts a log tail. This is what stops one tenant from reading another's
 * logs, which -- per the design spec's own warning -- typically carry more than a status value.
 *
 * <p><b>Streams end when the render becomes terminal (also binding, see the task 3 report's
 * "operational point"):</b> both endpoints watch {@link BlueMapRender} for {@link
 * RenderPhases#isTerminal}, and complete the SSE response the moment it is. Without this, a
 * browser tab left open after a render finishes would hold its connection -- and the Kubernetes
 * watch behind it -- open indefinitely.
 *
 * <p>Open to any authenticated caller of the resolved tenant ({@code @Secured(IS_AUTHENTICATED)})
 * rather than a specific role: design spec §10.3 lists {@code tenant-viewer} as read-only, and
 * reading a render's own progress/logs is exactly that baseline read access every tenant role
 * has -- there is nothing here a {@code tenant-viewer} should be denied that {@code
 * tenant-operator}/{@code tenant-owner} may see.
 */
@Controller("/api/renders")
@Secured(SecurityRule.IS_AUTHENTICATED)
class RenderStreamController {

    private final RenderRepository renderRepository;
    private final TenantResolver tenantResolver;
    private final LogSource logSource;
    private final PrincipalResolver principalResolver;

    RenderStreamController(
            RenderRepository renderRepository,
            TenantResolver tenantResolver,
            LogSource logSource,
            PrincipalResolver principalResolver) {
        this.renderRepository = renderRepository;
        this.tenantResolver = tenantResolver;
        this.logSource = logSource;
        this.principalResolver = principalResolver;
    }

    @Get(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM)
    Publisher<Event<RenderProgress>> events(Authentication authentication, @PathVariable String id) {
        BlueMapRender render = requireRender(authentication, id);
        String namespace = render.getMetadata().getNamespace();
        String resourceVersion = render.getMetadata().getResourceVersion();

        return new SseSource<Event<RenderProgress>>(sink -> {
            SseSource.Sink<RenderProgress> progressSink = eventSink(sink);
            // Emitted from the same read requireRender already did -- no extra API call -- so a
            // viewer sees a value immediately instead of waiting for the next status change.
            progressSink.next(RenderProgress.from(render));
            if (RenderPhases.isTerminal(render.getStatus().getPhase())) {
                progressSink.complete();
                return () -> {};
            }
            // Watching from this exact resourceVersion (not "from now") closes the gap between
            // the read above and the watch registration below: no update can land unobserved in
            // between.
            return renderRepository.watch(namespace, id, resourceVersion, progressWatcher(progressSink));
        });
    }

    @Get(value = "/{id}/logs", produces = MediaType.TEXT_EVENT_STREAM)
    Publisher<Event<String>> logs(Authentication authentication, @PathVariable String id) {
        BlueMapRender render = requireRender(authentication, id);
        String namespace = render.getMetadata().getNamespace();
        String jobName = render.getStatus().getJobName();
        String resourceVersion = render.getMetadata().getResourceVersion();
        boolean terminal = RenderPhases.isTerminal(render.getStatus().getPhase());

        return new SseSource<Event<String>>(sink -> {
            if (jobName == null || jobName.isBlank()) {
                // No job has been created yet (e.g. still Pending) -- nothing to tail. A client
                // sees an immediately-completed, empty stream and may retry once rendering starts.
                sink.complete();
                return () -> {};
            }
            AutoCloseable logHandle = logSource.tail(namespace, jobName, eventSink(sink));
            if (terminal) {
                return logHandle;
            }
            AutoCloseable watchHandle = renderRepository.watch(namespace, id, resourceVersion, terminationWatcher(sink));
            return combine(logHandle, watchHandle);
        });
    }

    /**
     * Resolves the caller's namespace and looks the render up in it -- and only in it. See the
     * class Javadoc for why this is the entire tenant-isolation mechanism for both endpoints.
     *
     * @throws HttpStatusException {@link HttpStatus#FORBIDDEN} if the caller carries no tenant
     *     claim at all (there is no default tenant to fall back to, {@link TenantResolver}); or
     *     {@link HttpStatus#NOT_FOUND} if {@code id} does not name a render in that namespace --
     *     including when it names one in a different tenant's namespace
     */
    private BlueMapRender requireRender(Authentication authentication, String id) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        String namespace;
        try {
            namespace = tenantResolver.namespaceFor(principal);
        } catch (ForbiddenException e) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        return renderRepository
                .find(namespace, id)
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "render '" + id + "' not found"));
    }

    private static Watcher<BlueMapRender> progressWatcher(SseSource.Sink<RenderProgress> sink) {
        return new Watcher<>() {
            @Override
            public void eventReceived(Action action, BlueMapRender resource) {
                if (action == Action.DELETED) {
                    sink.complete();
                    return;
                }
                sink.next(RenderProgress.from(resource));
                if (RenderPhases.isTerminal(resource.getStatus().getPhase())) {
                    sink.complete();
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    sink.error(cause);
                } else {
                    sink.complete();
                }
            }
        };
    }

    /** Only signals stream end -- the logs endpoint's own {@link LogSource} delivers the data. */
    private static Watcher<BlueMapRender> terminationWatcher(SseSource.Sink<Event<String>> sink) {
        return new Watcher<>() {
            @Override
            public void eventReceived(Action action, BlueMapRender resource) {
                if (action == Action.DELETED || RenderPhases.isTerminal(resource.getStatus().getPhase())) {
                    sink.complete();
                }
            }

            @Override
            public void onClose(WatcherException cause) {
                if (cause != null) {
                    sink.error(cause);
                } else {
                    sink.complete();
                }
            }
        };
    }

    /** Wraps a domain-value {@link SseSource.Sink} around one that expects SSE {@link Event}s. */
    private static <T> SseSource.Sink<T> eventSink(SseSource.Sink<Event<T>> downstream) {
        return new SseSource.Sink<>() {
            @Override
            public void next(T value) {
                downstream.next(Event.of(value));
            }

            @Override
            public void complete() {
                downstream.complete();
            }

            @Override
            public void error(Throwable throwable) {
                downstream.error(throwable);
            }
        };
    }

    /** Closes every handle, independently -- one failing to close must not skip the others. */
    private static AutoCloseable combine(AutoCloseable... closeables) {
        return () -> {
            for (AutoCloseable closeable : closeables) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // See SseSource.SingleSubscription.closeQuietly for why this is swallowed.
                }
            }
        };
    }
}
