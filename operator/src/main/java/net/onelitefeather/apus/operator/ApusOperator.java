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
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import net.onelitefeather.apus.operator.hosting.BlueMapHostingReconciler;
import net.onelitefeather.apus.operator.ingest.WorldIngestReconciler;
import net.onelitefeather.apus.operator.ingest.WorldSourceReconciler;
import net.onelitefeather.apus.operator.map.BlueMapMapReconciler;
import net.onelitefeather.apus.operator.render.BlueMapRenderReconciler;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;

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
 * registering reconcilers or starting the operator, is reported to stderr and ends the process
 * with a non-zero exit code -- never silently.
 */
public final class ApusOperator {

    private ApusOperator() {}

    public static void main(String[] args) {
        OperatorConfig config = OperatorConfig.fromEnvironment(System::getenv);

        KubernetesClient client;
        try {
            client = new KubernetesClientBuilder().build();
        } catch (RuntimeException e) {
            System.err.println("[apus-operator] failed to build a Kubernetes client: " + e.getMessage());
            System.exit(1);
            return;
        }

        try {
            run(client, config, new CountDownLatch(1), Runtime.getRuntime()::addShutdownHook);
        } catch (RuntimeException e) {
            System.err.println("[apus-operator] failed to start: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Registers the reconcilers, starts {@code operator} and then blocks until {@code
     * shutdownSignal} is released by the shutdown hook this method registers through {@code
     * shutdownHookRegistrar}.
     *
     * <p>Extracted from {@link #main} so a test can drive the whole run -- start, block, shut
     * down -- against a mock {@link KubernetesClient} and a hook it triggers itself, instead of a
     * real cluster and a real {@code SIGTERM}.
     */
    static void run(
            KubernetesClient client,
            OperatorConfig config,
            CountDownLatch shutdownSignal,
            Consumer<Thread> shutdownHookRegistrar) {
        Operator operator = new Operator(o -> o.withKubernetesClient(client));
        shutdownHookRegistrar.accept(
                new Thread(() -> shutdown(operator, client, shutdownSignal), "apus-operator-shutdown"));

        registerReconcilers(operator, client, config);
        operator.start();

        System.out.println("[apus-operator] started, watching Tenant/BlueMapMap/BlueMapRender/WorldSource/"
                + "WorldIngest/BlueMapHosting resources");

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
     * Stops {@code operator} and closes {@code client}, in that order, swallowing (but logging)
     * any failure from {@code stop()} so the client is still closed even if stopping the
     * controllers did not go cleanly.
     */
    private static void shutdown(Operator operator, KubernetesClient client, CountDownLatch shutdownSignal) {
        try {
            operator.stop();
        } catch (RuntimeException e) {
            System.err.println("[apus-operator] error while stopping: " + e.getMessage());
        } finally {
            client.close();
            shutdownSignal.countDown();
        }
    }
}
