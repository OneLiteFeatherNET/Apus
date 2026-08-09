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

import java.util.concurrent.atomic.AtomicBoolean;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * A minimal single-subscriber {@link Publisher} for driving Server-Sent Event streams from an
 * external push source -- a Kubernetes watch, a log tail -- without Reactor or RxJava. Neither is
 * a compile-time dependency of the {@code api} module (only the bare {@code reactive-streams} API
 * that {@code micronaut-http} itself depends on is; {@code reactor-core} only appears on the
 * runtime classpath, pulled in transitively by {@code micronaut-http-server-netty}, so it cannot
 * be imported from this module's main source without adding an explicit dependency -- out of
 * scope for this task, see the task 3 report).
 *
 * <p>Deliberately does not implement per-item backpressure: the first {@link Subscription#request}
 * call (whatever {@code n} it carries) is treated as "start delivering, and keep delivering
 * everything produced from now on". That is the right trade-off for a live status/log feed: a
 * slow consumer should see the newest state, not force the producer to buffer an ever-growing
 * backlog of stale ones. Micronaut's own SSE writer requests unbounded demand once at
 * subscription time in practice, so this never actually bites.
 *
 * @param <T> the event payload type
 */
final class SseSource<T> implements Publisher<T> {

    /** The producer-facing half of the channel a {@link Wiring} pushes values into. */
    interface Sink<T> {
        /** Delivers one value downstream. A no-op once the stream has ended. */
        void next(T value);

        /** Ends the stream successfully. A no-op if already ended. */
        void complete();

        /** Ends the stream with an error. A no-op if already ended. */
        void error(Throwable throwable);
    }

    /**
     * Connects an external push source to a {@link Sink} once a subscriber actually asks for
     * data, and returns the cleanup action for that connection -- run exactly once, whether the
     * stream ends because the source completed/errored, or because the subscriber cancelled
     * (e.g. a client closed its SSE connection).
     */
    @FunctionalInterface
    interface Wiring<T> {
        AutoCloseable wire(Sink<T> sink);
    }

    private final Wiring<T> wiring;

    SseSource(Wiring<T> wiring) {
        this.wiring = wiring;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber) {
        subscriber.onSubscribe(new SingleSubscription(subscriber));
    }

    /**
     * One subscription per subscriber, as required by this being a cold, single-use publisher
     * (a fresh {@link SseSource} is built per SSE request). {@code started}/{@code done} are
     * guarded independently on purpose: {@code started} only needs to fire {@link #wire} once
     * even under concurrent {@link Subscription#request} calls; {@code done} guards every path
     * that can end the stream (producer completion/error, subscriber cancellation) so cleanup
     * runs exactly once regardless of which one happens first.
     */
    private final class SingleSubscription implements Subscription, Sink<T> {

        private final Subscriber<? super T> subscriber;
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean done = new AtomicBoolean();
        private volatile AutoCloseable cleanup;

        private SingleSubscription(Subscriber<? super T> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                if (done.compareAndSet(false, true)) {
                    subscriber.onError(new IllegalArgumentException(
                            "reactive-streams §3.9: request(n) called with a non-positive n=" + n));
                }
                return;
            }
            if (started.compareAndSet(false, true)) {
                cleanup = wiring.wire(this);
            }
        }

        @Override
        public void cancel() {
            if (done.compareAndSet(false, true)) {
                closeQuietly();
            }
        }

        @Override
        public void next(T value) {
            if (!done.get()) {
                subscriber.onNext(value);
            }
        }

        @Override
        public void complete() {
            if (done.compareAndSet(false, true)) {
                subscriber.onComplete();
                closeQuietly();
            }
        }

        @Override
        public void error(Throwable throwable) {
            if (done.compareAndSet(false, true)) {
                subscriber.onError(throwable);
                closeQuietly();
            }
        }

        private void closeQuietly() {
            AutoCloseable toClose = cleanup;
            if (toClose != null) {
                try {
                    toClose.close();
                } catch (Exception ignored) {
                    // Best-effort cleanup (closing a Kubernetes Watch/LogWatch, joining a reader
                    // thread) -- the stream has already ended one way or another; a failure to
                    // release the underlying connection is not something the subscriber can act
                    // on, and is left to whatever the client library itself logs.
                }
            }
        }
    }
}
