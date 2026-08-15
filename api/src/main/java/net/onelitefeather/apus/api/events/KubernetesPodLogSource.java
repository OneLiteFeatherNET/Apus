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

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fallback {@link LogSource}: reads a render job's pod logs directly through the Kubernetes
 * client, used only when no Loki instance is configured (see {@link LogSourceFactory}). Needs
 * {@code get}/{@code list} on {@code pods} and {@code get} on {@code pods/log} in tenant
 * namespaces for the API's ServiceAccount -- permissions the Loki path avoids entirely (design
 * spec §11.1: "so that the API needs no direct pod access"). See the task 3 report for the
 * full trade-off.
 *
 * <p>Finds the pod via the {@code job-name} label Kubernetes sets on every pod a {@code Job}
 * creates (kept for backward compatibility alongside the newer {@code batch.kubernetes.io/
 * job-name} as of Kubernetes 1.27+; {@code RenderJobBuilder} in {@code :operator} does not
 * override it, so the plain, older key is used here). If the job's pod was replaced (a retry
 * after a crash, design spec §7.3) mid-stream, this does not follow the new pod -- a known gap,
 * see the report.
 */
final class KubernetesPodLogSource implements LogSource {

    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesPodLogSource.class);

    /** Set by Kubernetes itself on every Pod a Job creates -- not an Apus-specific label. */
    private static final String JOB_NAME_LABEL = "job-name";

    private final KubernetesClient client;

    KubernetesPodLogSource(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public AutoCloseable tail(String namespace, String jobName, SseSource.Sink<String> sink) {
        List<Pod> pods = client.pods()
                .inNamespace(namespace)
                .withLabel(JOB_NAME_LABEL, jobName)
                .list()
                .getItems();
        if (pods.isEmpty()) {
            LOGGER.warn("no pod found for render job '{}' in namespace '{}'", jobName, namespace);
            sink.error(new IllegalStateException("no pod found for render job '" + jobName + "'"));
            return () -> {};
        }

        String podName = pods.get(0).getMetadata().getName();
        LOGGER.debug("tailing pod '{}' in namespace '{}' for job '{}'", podName, namespace, jobName);
        LogWatch logWatch = client.pods().inNamespace(namespace).withName(podName).watchLog();
        Thread reader = Thread.ofVirtual().name("render-log-tail-" + jobName).start(() -> readLines(logWatch, sink));

        return () -> {
            logWatch.close();
            reader.interrupt();
        };
    }

    private static void readLines(LogWatch logWatch, SseSource.Sink<String> sink) {
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(logWatch.getOutput(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sink.next(line);
            }
            sink.complete();
        } catch (IOException e) {
            // Expected, not exceptional, when the cleanup handle above already closed logWatch
            // (client disconnected / render went terminal) -- the read is unblocked by the
            // stream closing and surfaces as an IOException. sink itself is already a no-op past
            // that point (SseSource.SingleSubscription.done), so this is harmless either way.
            sink.error(e);
        }
    }
}
