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
package net.onelitefeather.apus.operator.render;

import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import net.onelitefeather.apus.operator.api.Conditions;

/**
 * Turns a {@link BlueMapRender} into a running render {@link Job} (via {@link RenderJobBuilder}),
 * enforces the {@code concurrencyPolicy: Forbid} default so two renders never write the same
 * map's storage at once, and mirrors the render pod's progress into {@code status.progress}.
 *
 * <p><b>The map must have a bound bucket first.</b> {@link RenderJobBuilder} reads the bucket
 * name and endpoint straight out of {@code BlueMapMap.status.bucket} -- if that is empty (the
 * map does not exist yet, or {@link net.onelitefeather.apus.operator.map.BlueMapMapReconciler}
 * has not yet copied a bound claim's identity into it), submitting the job would hand the runner
 * an empty bucket name and it would simply fail. This reconciler refuses to create a job until
 * that status is populated, reporting {@value #MAP_NOT_READY_REASON} and rechecking later.
 *
 * <p><b>Concurrency lock ({@code Forbid}):</b> before creating a job, this reconciler lists
 * every other {@link Job} in the namespace carrying {@link RenderJobBuilder#MAP_LABEL} for the
 * same map. If any of them is still active (no {@code Succeeded}/{@code Failed} outcome yet), the
 * new render is left in {@code Pending} with reason {@value #CONCURRENT_RENDER_REASON} and
 * nothing is submitted -- two writers on the same map storage can leave it inconsistent.
 *
 * <p><b>Storage quota is not retried.</b> Ceph enforces a tenant's storage quota (see {@code
 * TenantReconciler}), so a render can fail because the bucket is simply full. That failure is
 * detected from the render pod's terminated container state (heuristically: its reason/message
 * mentioning "quota", case-insensitively -- Phase 1 does not yet define a dedicated exit code or
 * telemetry field for this, so this is a best-effort signal that should be tightened once one
 * exists) and reported via {@link #onQuotaExceeded(BlueMapRender, String)}: phase {@code Failed},
 * condition {@code StorageQuotaExceeded}, no further reschedule. Retrying against a full bucket
 * would just burn the same finite {@link RenderJobBuilder} backoff budget for nothing.
 *
 * <p><b>Ownership check, mirroring {@code TenantReconciler}:</b> the render job is named after
 * the {@link BlueMapRender} itself, so a render name can be reused after the original render is
 * deleted, and nothing stops a Job of that name existing for unrelated reasons. Before this
 * reconciler ever treats an existing Job as its own, it checks that Job's owner references for
 * one naming this exact render by name <em>and</em> UID (which {@link RenderJobBuilder} always
 * stamps on a job it builds). A mismatch (or a Job with no matching owner reference at all)
 * aborts with a {@code ResourceConflict} condition instead of silently adopting -- and thereby
 * losing track of -- a Job that belongs to something else.
 *
 * <p><b>Idempotent:</b> once a render's own job exists and is owned by it, reconciling again
 * never recreates the job -- it only polls progress and reflects the job's current state.
 */
@ControllerConfiguration
public class BlueMapRenderReconciler implements Reconciler<BlueMapRender> {

    /** Reason set on the {@code Ready} condition while the referenced map is not bound yet. */
    public static final String MAP_NOT_READY_REASON = "MapNotReady";

    /** Reason set on the {@code Ready} condition while another render for the same map is active. */
    public static final String CONCURRENT_RENDER_REASON = "ConcurrentRenderActive";

    /** Reason set on the {@code Ready} condition when an existing resource fails the ownership check. */
    public static final String RESOURCE_CONFLICT_REASON = "ResourceConflict";

    /** Reason set once the render job has exhausted its retries without a quota problem. */
    public static final String JOB_FAILED_REASON = "JobFailed";

    /** Reason set on the {@code Ready} condition once the render job completed. */
    public static final String SUCCEEDED_REASON = "Succeeded";

    /** Reason set on the {@code Ready} condition when a storage-quota failure ends the render. */
    public static final String STORAGE_QUOTA_EXCEEDED_REASON = "StorageQuotaExceeded";

    private static final String PENDING_PHASE = "Pending";
    private static final String RENDERING_PHASE = "Rendering";
    private static final String SUCCEEDED_PHASE = "Succeeded";
    private static final String FAILED_PHASE = "Failed";

    /** Reconciling a render already in one of these phases is a deliberate no-op. */
    private static final Set<String> TERMINAL_PHASES = Set.of(SUCCEEDED_PHASE, FAILED_PHASE);

    /**
     * The label the Kubernetes Job controller stamps onto every Pod it creates for a Job, set
     * to that Job's name. Used to find the render pod without modelling the Job -> Pod
     * relationship ourselves.
     */
    private static final String JOB_NAME_LABEL = "job-name";

    private static final Duration RECHECK_INTERVAL = Duration.ofSeconds(10);

    private final KubernetesClient client;
    private final OperatorConfig config;
    private final ProgressFetcher progressFetcher;

    public BlueMapRenderReconciler(KubernetesClient client, OperatorConfig config) {
        this(client, config, new HttpProgressFetcher());
    }

    /** Test seam: lets a fake progress source replace the real HTTP round-trip to the pod. */
    BlueMapRenderReconciler(KubernetesClient client, OperatorConfig config, ProgressFetcher progressFetcher) {
        this.client = client;
        this.config = config;
        this.progressFetcher = progressFetcher;
    }

    @Override
    public UpdateControl<BlueMapRender> reconcile(BlueMapRender render, Context<BlueMapRender> context) {
        String currentPhase = render.getStatus().getPhase();
        if (currentPhase != null && TERMINAL_PHASES.contains(currentPhase)) {
            return UpdateControl.noUpdate();
        }

        String namespace = render.getMetadata().getNamespace();
        String renderName = render.getMetadata().getName();
        String mapName = render.getSpec().getMapRef().getName();

        BlueMapMap map = (mapName == null || mapName.isBlank())
                ? null
                : client.resources(BlueMapMap.class).inNamespace(namespace).withName(mapName).get();
        if (map == null || !isBucketBound(map)) {
            return pending(
                    render,
                    MAP_NOT_READY_REASON,
                    "map '" + mapName + "' does not exist yet or has no bound bucket");
        }

        Job existingJob = client.batch().v1().jobs().inNamespace(namespace).withName(renderName).get();
        if (existingJob != null) {
            if (!ownedBySameRender(existingJob, render)) {
                return conflict(render, "Job", renderName);
            }
            return reconcileActiveJob(render, existingJob, namespace, renderName);
        }

        if (anotherActiveRenderJobExists(namespace, mapName, renderName)) {
            return pending(
                    render,
                    CONCURRENT_RENDER_REASON,
                    "another render for map '" + mapName + "' is already active (concurrencyPolicy: Forbid)");
        }

        String bucketSecretName = map.getStatus().getBucket().getSecretName();
        Job job = RenderJobBuilder.build(render, map, bucketSecretName, config);
        client.batch().v1().jobs().inNamespace(namespace).resource(job).createOr(NonDeletingOperation::update);

        render.getStatus().setJobName(renderName);
        render.getStatus().setPhase(RENDERING_PHASE);
        render.getStatus().setStartTime(Instant.now().toString());
        Conditions.set(
                render.getStatus().getConditions(), Conditions.ready(false, RENDERING_PHASE, "render job submitted"));
        return UpdateControl.patchStatus(render).rescheduleAfter(RECHECK_INTERVAL);
    }

    /**
     * Ends a render permanently because its storage quota was exceeded: sets phase {@code
     * Failed} and a {@code StorageQuotaExceeded} condition, without scheduling any further
     * reconciliation. Retrying would just fail the same way again against a bucket that is
     * still full (§12 of the design spec).
     *
     * <p>Public because a resource-quota failure is, by nature, observed from outside the
     * normal job-status polling this class does internally (see the class Javadoc) -- callers
     * with a more direct signal (e.g. a future admission response from Rook) can report it
     * through the exact same path.
     */
    public void onQuotaExceeded(BlueMapRender render, String message) {
        render.getStatus().setPhase(FAILED_PHASE);
        render.getStatus().setCompletionTime(Instant.now().toString());
        Conditions.set(
                render.getStatus().getConditions(),
                Conditions.ready(false, STORAGE_QUOTA_EXCEEDED_REASON, message));
    }

    private UpdateControl<BlueMapRender> reconcileActiveJob(
            BlueMapRender render, Job job, String namespace, String renderName) {
        Pod pod = findPod(namespace, renderName);

        if (pod != null) {
            Optional<String> quotaMessage = quotaExceededMessage(pod);
            if (quotaMessage.isPresent()) {
                onQuotaExceeded(render, quotaMessage.get());
                return UpdateControl.patchStatus(render);
            }
        }

        if (isJobSucceeded(job)) {
            render.getStatus().setPhase(SUCCEEDED_PHASE);
            render.getStatus().setCompletionTime(Instant.now().toString());
            Conditions.set(
                    render.getStatus().getConditions(),
                    Conditions.ready(true, SUCCEEDED_REASON, "render completed"));
            return UpdateControl.patchStatus(render);
        }
        if (isJobFailed(job)) {
            render.getStatus().setPhase(FAILED_PHASE);
            render.getStatus().setCompletionTime(Instant.now().toString());
            Conditions.set(
                    render.getStatus().getConditions(),
                    Conditions.ready(false, JOB_FAILED_REASON, "render job failed"));
            return UpdateControl.patchStatus(render);
        }

        if (pod != null) {
            progressFetcher.fetch(pod).flatMap(ProgressPoller::parse).ifPresent(progress -> applyProgress(render, progress));
        }

        render.getStatus().setPhase(RENDERING_PHASE);
        Conditions.set(
                render.getStatus().getConditions(), Conditions.ready(false, RENDERING_PHASE, "render job is running"));
        return UpdateControl.patchStatus(render).rescheduleAfter(RECHECK_INTERVAL);
    }

    private static void applyProgress(BlueMapRender render, ProgressPoller.RenderProgress progress) {
        var status = render.getStatus().getProgress();
        status.setPercent(progress.progress());
        status.setCurrentMap(progress.currentMap());
        status.setEtaSeconds(progress.etaSeconds());
        status.setDegraded(progress.degraded());
    }

    private static boolean isBucketBound(BlueMapMap map) {
        String bucketName = map.getStatus().getBucket().getName();
        return bucketName != null && !bucketName.isBlank();
    }

    private boolean anotherActiveRenderJobExists(String namespace, String mapName, String excludeRenderName) {
        List<Job> jobs = client.batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(RenderJobBuilder.MAP_LABEL, mapName)
                .list()
                .getItems();
        for (Job job : jobs) {
            if (excludeRenderName.equals(job.getMetadata().getName())) {
                continue;
            }
            if (!isJobSucceeded(job) && !isJobFailed(job)) {
                return true;
            }
        }
        return false;
    }

    private Pod findPod(String namespace, String renderName) {
        List<Pod> pods = client.pods()
                .inNamespace(namespace)
                .withLabel(JOB_NAME_LABEL, renderName)
                .list()
                .getItems();
        return pods.isEmpty() ? null : pods.get(0);
    }

    /**
     * Best-effort detection of a storage-quota failure from the render pod's terminated
     * container state. See the class Javadoc for why this is a heuristic rather than a defined
     * contract.
     */
    private static Optional<String> quotaExceededMessage(Pod pod) {
        if (pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
            return Optional.empty();
        }
        for (ContainerStatus containerStatus : pod.getStatus().getContainerStatuses()) {
            ContainerStateTerminated terminated =
                    containerStatus.getState() == null ? null : containerStatus.getState().getTerminated();
            if (terminated == null) {
                continue;
            }
            String reason = terminated.getReason() == null ? "" : terminated.getReason();
            String message = terminated.getMessage() == null ? "" : terminated.getMessage();
            if ((reason + " " + message).toLowerCase(Locale.ROOT).contains("quota")) {
                return Optional.of(!message.isBlank() ? message : reason);
            }
        }
        return Optional.empty();
    }

    private static boolean isJobSucceeded(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return false;
        }
        return (status.getSucceeded() != null && status.getSucceeded() > 0) || hasCondition(status, "Complete");
    }

    private static boolean isJobFailed(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return false;
        }
        return (status.getFailed() != null && status.getFailed() > 0) || hasCondition(status, "Failed");
    }

    private static boolean hasCondition(JobStatus status, String type) {
        List<JobCondition> conditions = status.getConditions();
        if (conditions == null) {
            return false;
        }
        return conditions.stream().anyMatch(c -> type.equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /**
     * Checks whether an existing Job's owner references identify it as already belonging to
     * the render currently being reconciled. Both the name and the UID must match -- see the
     * class Javadoc's "Ownership check" section.
     */
    private static boolean ownedBySameRender(Job job, BlueMapRender render) {
        String renderUid = render.getMetadata().getUid();
        if (renderUid == null) {
            return false;
        }
        List<OwnerReference> owners = job.getMetadata().getOwnerReferences();
        if (owners == null) {
            return false;
        }
        return owners.stream()
                .anyMatch(ref -> "BlueMapRender".equals(ref.getKind())
                        && Objects.equals(render.getMetadata().getName(), ref.getName())
                        && Objects.equals(renderUid, ref.getUid()));
    }

    private static UpdateControl<BlueMapRender> pending(BlueMapRender render, String reason, String message) {
        render.getStatus().setPhase(PENDING_PHASE);
        Conditions.set(render.getStatus().getConditions(), Conditions.ready(false, reason, message));
        return UpdateControl.patchStatus(render).rescheduleAfter(RECHECK_INTERVAL);
    }

    /**
     * Aborts the reconciliation with a {@code ResourceConflict} condition, naming the resource
     * that already exists but is not owned by this render. Nothing further is created or
     * updated -- see {@code TenantReconciler}'s identical {@code conflict()} method.
     */
    private static UpdateControl<BlueMapRender> conflict(BlueMapRender render, String resourceKind, String resourceName) {
        render.getStatus().setPhase(PENDING_PHASE);
        Conditions.set(
                render.getStatus().getConditions(),
                Conditions.ready(
                        false,
                        RESOURCE_CONFLICT_REASON,
                        "existing " + resourceKind + " '" + resourceName
                                + "' is not owned by this render; refusing to adopt it"));
        return UpdateControl.patchStatus(render).rescheduleAfter(RECHECK_INTERVAL);
    }

    /** Fetches the raw {@code /progress} response body from a render pod, if reachable. */
    @FunctionalInterface
    interface ProgressFetcher {
        Optional<String> fetch(Pod pod);
    }

    /**
     * Polls the Phase 1 telemetry addon's {@code /progress} endpoint directly on the pod IP.
     * The port is not read from configuration: this module has no compile dependency on {@code
     * telemetry-addon}, so the default from {@code TelemetryConfig.DEFAULT_PORT} /
     * {@code APUS_TELEMETRY_PORT} is duplicated here as a constant instead.
     */
    private static final class HttpProgressFetcher implements ProgressFetcher {

        /** Mirrors {@code net.onelitefeather.apus.telemetry.TelemetryConfig.DEFAULT_PORT}. */
        private static final int TELEMETRY_PORT = 8099;

        private static final Duration TIMEOUT = Duration.ofSeconds(2);

        private final HttpClient httpClient =
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

        @Override
        public Optional<String> fetch(Pod pod) {
            String podIp = pod.getStatus() == null ? null : pod.getStatus().getPodIP();
            if (podIp == null || podIp.isBlank()) {
                return Optional.empty();
            }
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("http://" + podIp + ":" + TELEMETRY_PORT + "/progress"))
                        .timeout(TIMEOUT)
                        .GET()
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return response.statusCode() == 200 ? Optional.of(response.body()) : Optional.empty();
            } catch (IOException e) {
                // The pod may still be starting, or the telemetry addon may not be reachable
                // yet -- expected during the early phase of a render, must not fail
                // reconciliation.
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }
}
