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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Exercises the hand-rolled reactive-streams plumbing directly (no Reactor/RxJava on this
 * module's compile classpath, see {@link SseSource}'s Javadoc) -- the mechanism both SSE
 * endpoints in {@link RenderStreamController} rely on for "deliver values as they happen, and
 * release the underlying watch/log tail exactly once, however the stream ends".
 */
class SseSourceTest {

    /** Captures every signal a real SSE writer would otherwise consume. */
    private static class RecordingSubscriber implements Subscriber<String> {
        final List<String> values = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override
        public void onSubscribe(Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(String value) {
            values.add(value);
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

    private static final class RecordingCleanup implements AutoCloseable {
        final AtomicBoolean closed = new AtomicBoolean();

        @Override
        public void close() {
            closed.set(true);
        }
    }

    @Test
    void wiresUpOnlyAfterASubscriberRequestsDemand() {
        AtomicBoolean wired = new AtomicBoolean();
        SseSource<String> source = new SseSource<>(sink -> {
            wired.set(true);
            return () -> {};
        });

        RecordingSubscriber subscriber = new RecordingSubscriber() {
            @Override
            public void onSubscribe(Subscription s) {
                // Deliberately does not request -- wiring must not have happened yet.
            }
        };
        source.subscribe(subscriber);

        assertFalse(wired.get());
    }

    @Test
    void deliversEveryValuePushedThroughTheSink() {
        SseSource.Sink<String>[] captured = new SseSource.Sink[1];
        SseSource<String> source = new SseSource<>(sink -> {
            captured[0] = sink;
            return () -> {};
        });

        RecordingSubscriber subscriber = new RecordingSubscriber();
        source.subscribe(subscriber);

        captured[0].next("first");
        captured[0].next("second");

        assertEquals(List.of("first", "second"), subscriber.values);
        assertFalse(subscriber.completed);
    }

    @Test
    void completingTheSinkCompletesTheSubscriberAndRunsCleanupExactlyOnce() {
        RecordingCleanup cleanup = new RecordingCleanup();
        SseSource.Sink<String>[] captured = new SseSource.Sink[1];
        SseSource<String> source = new SseSource<>(sink -> {
            captured[0] = sink;
            return cleanup;
        });

        RecordingSubscriber subscriber = new RecordingSubscriber();
        source.subscribe(subscriber);

        captured[0].complete();
        captured[0].complete(); // must be a no-op the second time
        captured[0].next("too late"); // must be dropped, not delivered

        assertTrue(subscriber.completed);
        assertTrue(cleanup.closed.get());
        assertTrue(subscriber.values.isEmpty());
    }

    @Test
    void erroringTheSinkPropagatesTheThrowableAndRunsCleanup() {
        RecordingCleanup cleanup = new RecordingCleanup();
        SseSource.Sink<String>[] captured = new SseSource.Sink[1];
        SseSource<String> source = new SseSource<>(sink -> {
            captured[0] = sink;
            return cleanup;
        });
        RecordingSubscriber subscriber = new RecordingSubscriber();
        source.subscribe(subscriber);

        RuntimeException boom = new RuntimeException("watch failed");
        captured[0].error(boom);

        assertEquals(boom, subscriber.error);
        assertTrue(cleanup.closed.get());
    }

    @Test
    void cancellingTheSubscriptionRunsCleanupWithoutCompletingOrErroring() {
        // The client-disconnect path: no producer-side signal ever arrives, only cancel().
        RecordingCleanup cleanup = new RecordingCleanup();
        SseSource<String> source = new SseSource<>(sink -> cleanup);

        Subscription[] captured = new Subscription[1];
        source.subscribe(new RecordingSubscriber() {
            @Override
            public void onSubscribe(Subscription s) {
                captured[0] = s;
                s.request(1);
            }
        });

        captured[0].cancel();

        assertTrue(cleanup.closed.get());
    }

    @Test
    void nonPositiveRequestFailsTheStreamWithoutWiringAnything() {
        AtomicBoolean wired = new AtomicBoolean();
        SseSource<String> source = new SseSource<>(sink -> {
            wired.set(true);
            return () -> {};
        });

        Subscription[] captured = new Subscription[1];
        RecordingSubscriber subscriber = new RecordingSubscriber() {
            @Override
            public void onSubscribe(Subscription s) {
                captured[0] = s;
            }
        };
        source.subscribe(subscriber);
        captured[0].request(0);

        assertFalse(wired.get());
        assertTrue(subscriber.error instanceof IllegalArgumentException);
        assertTrue(subscriber.values.isEmpty());
    }
}
