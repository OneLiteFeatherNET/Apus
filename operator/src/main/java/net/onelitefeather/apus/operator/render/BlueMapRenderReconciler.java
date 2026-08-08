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
import io.fabric8.kubernetes.client.KubernetesClientException;
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
 * <p><b>Concurrency lock ({@code Forbid}):</b> listing Jobs and then creating one is two separate
 * API calls with no atomicity between them -- two {@link BlueMapRender}s for the same map
 * reconciled at nearly the same time could both observe "nothing active yet" before either of
 * them has created its Job. The primary lock is therefore an optimistic write to {@link
 * net.onelitefeather.apus.operator.api.BlueMapMapStatus#getLatestRender() BlueMapMap.status.latestRender}:
 * before creating a Job, this reconciler claims that field for itself via {@link
 * MapLockClaimer#claim(BlueMapMap)}, an {@code updateStatus()} call the API server rejects with a
 * 409 Conflict if the map's status changed (i.e. someone else claimed it first) since this
 * reconciler last read it. The reconciler that loses the conflict creates no Job and stays in
 * {@code Pending} with reason {@value #CONCURRENT_RENDER_REASON}, to be rechecked later. If the
 * currently-recorded render is itself still active (fetched live by name, not trusted from the
 * possibly-stale copy in {@code latestRender.phase}), a second render does not even attempt the
 * write -- same outcome. A terminal ({@code Succeeded}/{@code Failed}) or since-deleted recorded
 * render does not block a new claim. As a secondary safeguard (in case the status field was lost
 * or never written, e.g. by a manual edit), this reconciler also lists every other {@link Job} in
 * the namespace carrying {@link RenderJobBuilder#MAP_LABEL} for the same map and refuses to
 * proceed if one is still active -- but this check alone is not race-free, which is exactly why
 * the status-based claim exists.
 *
 * <p><b>Storage quota is not retried.</b> Ceph enforces a tenant's storage quota (see {@code
 * TenantReconciler}), so a render can fail because the bucket is simply full. That failure is
 * detected from the render pod's terminated container state (heuristically: its reason/message
 * matching a narrow set of S3-quota-specific patterns, case-insensitively -- see {@link
 * #quotaExceededMessage(Pod)}. Phase 1 does not yet define a dedicated exit code or telemetry
 * field for this, so this remains a best-effort signal, not a load-bearing contract, until the
 * runner image grows one) and reported via {@link #onQuotaExceeded(BlueMapRender, String)}: phase
 * {@code Failed}, condition {@code StorageQuotaExceeded}, no further reschedule. Retrying against
 * a full bucket would just burn the same finite {@link RenderJobBuilder} backoff budget for
 * nothing. See design spec §15 for the open point of a dedicated signal.
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
    private final MapLockClaimer mapLockClaimer;

    public BlueMapRenderReconciler(KubernetesClient client, OperatorConfig config) {
        this(client, config, new HttpProgressFetcher());
    }

    /** Test seam: lets a fake progress source replace the real HTTP round-trip to the pod. */
    BlueMapRenderReconciler(KubernetesClient client, OperatorConfig config, ProgressFetcher progressFetcher) {
        this(client, config, progressFetcher, map -> client.resources(BlueMapMap.class)
                .inNamespace(map.getMetadata().getNamespace())
                .resource(map)
                .updateStatus());
    }

    /** Test seam: lets a fake claimer simulate a 409 Conflict from a competing reconciler. */
    BlueMapRenderReconciler(
            KubernetesClient client, OperatorConfig config, ProgressFetcher progressFetcher, MapLockClaimer mapLockClaimer) {
        this.client = client;
        this.config = config;
        this.progressFetcher = progressFetcher;
        this.mapLockClaimer = mapLockClaimer;
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

        if (!tryClaimMap(map, namespace, renderName)) {
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

    /**
     * Attempts to claim {@code map.status.latestRender} for {@code renderName}, the primary
     * defence of the concurrency lock described in the class Javadoc.
     *
     * <p>Three outcomes are all "claimed, proceed": the field is empty (no prior render), it
     * already names this exact render (a retry after a previous claim whose Job creation never
     * happened, e.g. a crash in between -- re-claiming is a harmless no-op), or the previously
     * recorded render is no longer active. In every other case -- another render is recorded and
     * still active, or the optimistic {@code updateStatus()} loses a race to a competing claim
     * (HTTP 409) -- this returns {@code false} and creates nothing.
     */
    private boolean tryClaimMap(BlueMapMap map, String namespace, String renderName) {
        String recordedName = map.getStatus().getLatestRender().getName();
        if (renderName.equals(recordedName)) {
            return true;
        }
        if (recordedName != null && !recordedName.isBlank() && isRenderStillActive(namespace, recordedName)) {
            return false;
        }

        map.getStatus().getLatestRender().setName(renderName);
        map.getStatus().getLatestRender().setPhase(RENDERING_PHASE);
        try {
            mapLockClaimer.claim(map);
            return true;
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                // Lost the race: another reconciler's claim landed first and changed the map's
                // resourceVersion out from under us. Whoever gets the conflict has lost -- see
                // the class Javadoc.
                return false;
            }
            throw e;
        }
    }

    /**
     * Live lookup of whether the render currently recorded in {@code latestRender} still counts
     * as active, used by {@link #tryClaimMap}. Deliberately re-fetches the actual {@link
     * BlueMapRender} instead of trusting {@code latestRender.phase}: that field is only a
     * snapshot written at claim time and is never updated again, so trusting it here would make
     * every render after the first permanently find the map "still active" once the recorded
     * phase is stale. A missing render (deleted since it was recorded) counts as not active.
     */
    private boolean isRenderStillActive(String namespace, String renderName) {
        BlueMapRender recorded =
                client.resources(BlueMapRender.class).inNamespace(namespace).withName(renderName).get();
        if (recorded == null) {
            return false;
        }
        String phase = recorded.getStatus().getPhase();
        return phase == null || !TERMINAL_PHASES.contains(phase);
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
     * S3 error codes/phrases that unambiguously mean a quota was hit -- no further context
     * needed to treat a message mentioning one of these as a storage-quota failure.
     */
    private static final Set<String> UNAMBIGUOUS_QUOTA_TOKENS = Set.of("quotaexceeded", "exceededquota");

    /**
     * Terms that tie a bare mention of "quota" to S3/object storage specifically, as opposed to
     * some unrelated Kubernetes quota (ephemeral-storage, pod count, ...). Required alongside a
     * plain "quota" match -- see {@link #quotaExceededMessage(Pod)}.
     */
    private static final Set<String> S3_CONTEXT_TOKENS = Set.of("s3", "bucket", "rgw", "ceph", "object storage");

    /**
     * Best-effort detection of a storage-quota failure from the render pod's terminated
     * container state's reason and message.
     *
     * <p><b>This is a heuristic, not a defined contract.</b> Two problems limit what it can
     * reliably see, and both remain open (design spec §15) until the runner image grows a proper
     * signal (e.g. a dedicated exit code):
     *
     * <ul>
     *   <li>The Kubelet's terminated-container {@code reason} comes from a small fixed vocabulary
     *       ({@code Error}, {@code OOMKilled}, ...) and never mentions "quota". The {@code
     *       message} is only populated if the container writes to {@code
     *       /dev/termination-log}, which does not happen by default -- {@link RenderJobBuilder}
     *       sets {@code terminationMessagePolicy: FallbackToLogsOnError} precisely so a failing
     *       container's last log lines end up here instead of an empty message.
     *   <li>A bare substring match on "quota" would be too broad: an unrelated failure (e.g. an
     *       ephemeral-storage quota killing the pod) also contains that word and must not be
     *       reported -- wrongly -- as a permanent {@code StorageQuotaExceeded}, since that phase
     *       is never retried.
     * </ul>
     *
     * <p>To stay narrow, this only matches an unambiguous S3 quota error code/phrase ({@link
     * #UNAMBIGUOUS_QUOTA_TOKENS}, e.g. the AWS S3 {@code QuotaExceeded} error code), or the word
     * "quota" combined with an S3/object-storage-specific term ({@link #S3_CONTEXT_TOKENS}).
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
            String combined = (reason + " " + message).toLowerCase(Locale.ROOT);
            boolean unambiguousQuotaError = UNAMBIGUOUS_QUOTA_TOKENS.stream().anyMatch(combined::contains);
            boolean quotaWithS3Context =
                    combined.contains("quota") && S3_CONTEXT_TOKENS.stream().anyMatch(combined::contains);
            if (unambiguousQuotaError || quotaWithS3Context) {
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
     * Performs the optimistic {@code updateStatus()} call {@link #tryClaimMap} relies on. The
     * real implementation is a one-line delegation to the Kubernetes client; it exists as an
     * interface purely so tests can inject a fake that throws a simulated {@link
     * KubernetesClientException} (HTTP 409) to exercise the conflict path deterministically --
     * the fabric8 mock server used elsewhere in this test suite does not enforce optimistic
     * concurrency (no resourceVersion check on update), so a real conflict cannot be reproduced
     * against it.
     */
    @FunctionalInterface
    interface MapLockClaimer {
        void claim(BlueMapMap map);
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
