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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.sse.Event;
import io.micronaut.security.authentication.Authentication;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Covers the task 3 brief's three binding requirements directly against {@link
 * RenderStreamController}, using hand-written fakes for {@link RenderRepository} and {@link
 * LogSource} instead of a mocking framework or a Micronaut test context -- neither {@code
 * micronaut-test-junit5} nor a mocking library is a test dependency of the {@code api} module
 * (see the task 3 report), so these tests call the controller's methods directly rather than
 * going through an embedded server.
 */
class RenderStreamControllerTest {

    private static final Authentication VIEWER =
            Authentication.build("carol", List.of("tenant-viewer"), Map.of("organization", "acme"));

    private static final class RecordingSubscriber<T> implements Subscriber<Event<T>> {
        final List<T> values = new ArrayList<>();
        Throwable error;
        boolean completed;
        Subscription subscription;

        @Override
        public void onSubscribe(Subscription s) {
            subscription = s;
        }

        @Override
        public void onNext(Event<T> event) {
            values.add(event.getData());
        }

        @Override
        public void onError(Throwable t) {
            error = t;
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }

    private static final class FakeRenderRepository implements RenderRepository {
        private final Map<String, BlueMapRender> renders = new HashMap<>();
        Watcher<BlueMapRender> capturedWatcher;
        boolean watchCalled;
        boolean watchClosed;

        void put(String namespace, String name, BlueMapRender render) {
            renders.put(namespace + "/" + name, render);
        }

        @Override
        public Optional<BlueMapRender> find(String namespace, String name) {
            return Optional.ofNullable(renders.get(namespace + "/" + name));
        }

        @Override
        public Watch watch(String namespace, String name, String resourceVersion, Watcher<BlueMapRender> watcher) {
            watchCalled = true;
            capturedWatcher = watcher;
            return () -> watchClosed = true;
        }
    }

    private static final class FakeLogSource implements LogSource {
        boolean tailCalled;
        boolean closed;
        SseSource.Sink<String> capturedSink;

        @Override
        public AutoCloseable tail(String namespace, String jobName, SseSource.Sink<String> sink) {
            tailCalled = true;
            capturedSink = sink;
            return () -> closed = true;
        }
    }

    private static BlueMapRender render(String namespace, String name, String phase) {
        BlueMapRender render = new BlueMapRender();
        render.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace)
                .withResourceVersion("1")
                .build());
        render.getStatus().setPhase(phase);
        render.getStatus().setJobName(name);
        return render;
    }

    // -- GET /api/renders/{id}/events -----------------------------------------------------

    @Test
    void progressStreamDeliversTheCurrentSnapshotImmediatelyAndAgainOnEachStatusChange() {
        BlueMapRender render = render("bluemap-acme", "render-1", "Rendering");
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render);
        RenderStreamController controller =
                new RenderStreamController(repository, new TenantResolver(), new FakeLogSource());

        Publisher<Event<RenderProgress>> publisher = controller.events(VIEWER, "render-1");
        RecordingSubscriber<RenderProgress> subscriber = new RecordingSubscriber<>();
        publisher.subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        // Initial snapshot, from the same read that already proved the render exists.
        assertEquals(1, subscriber.values.size());
        assertEquals("Rendering", subscriber.values.get(0).phase());

        // The operator writes a new progress value -- the watch (not a poll) is what delivers it.
        render.getStatus().getProgress().setPercent(42.0);
        repository.capturedWatcher.eventReceived(Watcher.Action.MODIFIED, render);

        assertEquals(2, subscriber.values.size());
        assertEquals(42.0, subscriber.values.get(1).percent());
        assertFalse(subscriber.completed);
    }

    @Test
    void progressStreamEndsAndClosesTheWatchWhenTheRenderBecomesTerminal() {
        BlueMapRender render = render("bluemap-acme", "render-1", "Rendering");
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render);
        RenderStreamController controller =
                new RenderStreamController(repository, new TenantResolver(), new FakeLogSource());

        RecordingSubscriber<RenderProgress> subscriber = new RecordingSubscriber<>();
        controller.events(VIEWER, "render-1").subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        render.getStatus().setPhase("Succeeded");
        repository.capturedWatcher.eventReceived(Watcher.Action.MODIFIED, render);

        assertTrue(subscriber.completed, "subscriber must see onComplete once the render is terminal");
        assertTrue(repository.watchClosed, "the Kubernetes watch must be closed, not left open");
    }

    @Test
    void progressStreamOfAnAlreadyTerminalRenderCompletesWithoutEverWatching() {
        BlueMapRender render = render("bluemap-acme", "render-1", "Succeeded");
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render);
        RenderStreamController controller =
                new RenderStreamController(repository, new TenantResolver(), new FakeLogSource());

        RecordingSubscriber<RenderProgress> subscriber = new RecordingSubscriber<>();
        controller.events(VIEWER, "render-1").subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        assertEquals(1, subscriber.values.size());
        assertTrue(subscriber.completed);
        assertFalse(repository.watchCalled, "nothing left to watch for a render that is already done");
    }

    @Test
    void aRenderInAForeignTenantsNamespaceIs404BeforeAnyWatchOpens() {
        FakeRenderRepository repository = new FakeRenderRepository();
        // Exists, but only in a different tenant's namespace -- never looked up there.
        repository.put("bluemap-globex", "render-1", render("bluemap-globex", "render-1", "Rendering"));
        RenderStreamController controller =
                new RenderStreamController(repository, new TenantResolver(), new FakeLogSource());

        HttpStatusException e =
                assertThrows(HttpStatusException.class, () -> controller.events(VIEWER, "render-1"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertFalse(repository.watchCalled, "must not have looked in any other namespace to find it");
    }

    @Test
    void aPrincipalWithNoTenantClaimIsForbiddenBeforeAnyLookupHappens() {
        FakeRenderRepository repository = new FakeRenderRepository();
        RenderStreamController controller =
                new RenderStreamController(repository, new TenantResolver(), new FakeLogSource());
        Authentication noTenant = Authentication.build("root", List.of("platform-admin"), Map.of());

        HttpStatusException e =
                assertThrows(HttpStatusException.class, () -> controller.events(noTenant, "render-1"));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    // -- GET /api/renders/{id}/logs --------------------------------------------------------

    @Test
    void logStreamTailsTheJobAndEndsWhenTheRenderBecomesTerminal() {
        BlueMapRender render = render("bluemap-acme", "render-1", "Rendering");
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render);
        FakeLogSource logSource = new FakeLogSource();
        RenderStreamController controller = new RenderStreamController(repository, new TenantResolver(), logSource);

        RecordingSubscriber<String> subscriber = new RecordingSubscriber<>();
        controller.logs(VIEWER, "render-1").subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        assertTrue(logSource.tailCalled);
        logSource.capturedSink.next("[bluemap] rendering overworld: 12%");
        assertEquals(List.of("[bluemap] rendering overworld: 12%"), subscriber.values);

        render.getStatus().setPhase("Succeeded");
        repository.capturedWatcher.eventReceived(Watcher.Action.MODIFIED, render);

        assertTrue(subscriber.completed);
        assertTrue(logSource.closed, "the log tail must be released, not left open");
        assertTrue(repository.watchClosed, "the termination watch must be released too");
    }

    @Test
    void logStreamOfAForeignTenantsRenderIs404BeforeAnyLogTailOpens() {
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-globex", "render-1", render("bluemap-globex", "render-1", "Rendering"));
        FakeLogSource logSource = new FakeLogSource();
        RenderStreamController controller = new RenderStreamController(repository, new TenantResolver(), logSource);

        HttpStatusException e = assertThrows(HttpStatusException.class, () -> controller.logs(VIEWER, "render-1"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertFalse(logSource.tailCalled, "logs of a render outside the caller's tenant must never be read");
    }

    @Test
    void anUnrelatedRenderIdIs404TheSameWayAForeignTenantsIs() {
        // No render by this id exists anywhere -- proves the 404 does not leak "exists elsewhere"
        // vs. "does not exist at all" as two different outcomes.
        FakeRenderRepository repository = new FakeRenderRepository();
        RenderStreamController controller =
                new RenderStreamController(repository, new TenantResolver(), new FakeLogSource());

        HttpStatusException e =
                assertThrows(HttpStatusException.class, () -> controller.events(VIEWER, "does-not-exist"));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
    }

    @Test
    void logStreamPropagatesAWatcherCloseErrorAsAStreamError() {
        BlueMapRender render = render("bluemap-acme", "render-1", "Rendering");
        FakeRenderRepository repository = new FakeRenderRepository();
        repository.put("bluemap-acme", "render-1", render);
        RenderStreamController controller =
                new RenderStreamController(repository, new TenantResolver(), new FakeLogSource());

        RecordingSubscriber<RenderProgress> subscriber = new RecordingSubscriber<>();
        controller.events(VIEWER, "render-1").subscribe(subscriber);
        subscriber.subscription.request(Long.MAX_VALUE);

        WatcherException cause = new WatcherException("connection reset");
        repository.capturedWatcher.onClose(cause);

        assertEquals(cause, subscriber.error);
        assertFalse(subscriber.completed);
    }
}
