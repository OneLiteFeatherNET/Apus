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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Covers the process-lifetime half of {@link ApusOperator#run} -- that a started operator keeps
 * the calling thread alive instead of falling off the end of {@code main}.
 *
 * <p>This is the defect this class exists for: {@link io.javaoperatorsdk.operator.Operator#start()}
 * hands its controllers to background (daemon) threads and returns immediately. When {@code main}
 * returned right after it, the JVM had no non-daemon thread left, exited with code 0, and
 * Kubernetes restarted the container roughly every minute -- reconciling correctly in between,
 * but never staying up. {@code ApusOperatorTest} could not see any of this: it only exercises
 * {@link ApusOperator#registerReconcilers}, and the {@code *IntegrationTest} classes run under
 * JOSDK's {@code LocallyRunOperatorExtension}, which keeps the operator alive on its own.
 *
 * <p>The shutdown hook is captured rather than registered with the JVM, so the test can trigger
 * it the way a {@code SIGTERM} would and assert that it actually releases the wait -- a hook that
 * stops the operator but leaves {@code main} parked forever would still get the pod
 * {@code SIGKILL}ed at the end of its grace period.
 *
 * <p>The OpenTelemetry SDK handed to {@code run} is a plain, exporter-less one built here rather
 * than the autoconfigured one {@code main} builds: this test is about the process lifetime, and
 * the SDK's only role in it is being closed by the very same hook. That it <em>is</em> closed
 * there, instead of by a second hook of its own, is what {@link
 * ApusOperator#initTelemetry()} disables autoconfiguration's shutdown hook for.
 */
@EnableKubernetesMockClient(crud = true)
class ApusOperatorRunTest {

    /**
     * How long to let the operator run before checking it is still there. The defect made the run
     * return within milliseconds of {@code start()}, so anything above "immediately" separates the
     * two cases; a full second keeps that margin comfortable on a loaded CI machine without making
     * the test meaningfully slow.
     */
    private static final Duration SETTLE = Duration.ofSeconds(1);

    private static final Duration SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);

    KubernetesClient client;

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void staysAliveAfterStartAndReturnsOnlyWhenTheShutdownHookRuns() throws InterruptedException {
        CountDownLatch shutdownSignal = new CountDownLatch(1);
        AtomicReference<Thread> shutdownHook = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        OpenTelemetrySdk telemetry = OpenTelemetrySdk.builder().build();

        Thread operatorThread = new Thread(
                () -> ApusOperator.run(client, OperatorConfig.defaults(), telemetry, shutdownSignal, shutdownHook::set),
                "apus-operator-main");
        operatorThread.setUncaughtExceptionHandler((thread, thrown) -> failure.set(thrown));
        operatorThread.start();

        operatorThread.join(SETTLE.toMillis());

        if (failure.get() != null) {
            throw new AssertionError("the operator run failed instead of blocking", failure.get());
        }
        assertTrue(
                operatorThread.isAlive(),
                "run() returned after start() instead of blocking -- the JVM would now exit with code 0 "
                        + "and Kubernetes would restart the container");

        Thread hook = shutdownHook.get();
        assertNotNull(hook, "run() must register a shutdown hook so SIGTERM stops the operator cleanly");

        hook.start();
        hook.join(SHUTDOWN_TIMEOUT.toMillis());
        operatorThread.join(SHUTDOWN_TIMEOUT.toMillis());

        assertFalse(
                operatorThread.isAlive(),
                "the shutdown hook stopped the operator but did not release the wait -- the pod would sit "
                        + "out its whole termination grace period and then be SIGKILLed");
    }
}
