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
package net.onelitefeather.apus.operator.ingest;

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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.onelitefeather.apus.ingest.BundlePath;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import net.onelitefeather.apus.operator.api.BundleRef;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.WorldIngest;
import net.onelitefeather.apus.operator.api.WorldSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;

/**
 * Turns a {@link WorldIngest} into a running ingest {@link Job} (via {@link IngestJobBuilder}),
 * enforces that only one ingest for a given {@link WorldSource} runs at a time, mirrors the
 * job's progress into {@code status}, and on success updates {@code
 * WorldSource.status.latestBundle} plus enforces {@code WorldSource.spec.retention}.
 *
 * <p>Structurally this is {@code BlueMapRenderReconciler}'s exact shape, applied one level up the
 * chain (ingest run -> source, instead of render run -> map): same job-ownership check, same
 * optimistic-lock concurrency guard, same "reconciling a terminal ingest is a no-op" rule. See
 * that class's Javadoc for the full reasoning; only what differs is called out below.
 *
 * <p><b>Concurrency lock:</b> before creating a Job, this reconciler claims {@code
 * WorldSource.status.activeIngest} for its own ingest name via an optimistic {@code
 * updateStatus()} -- a 409 Conflict means another ingest claimed it first. If the
 * currently-recorded ingest is itself still active (fetched live by name), a second ingest does
 * not even attempt the write.
 *
 * <p><b>Source-side bookkeeping happens before the ingest is marked terminal.</b> Updating {@code
 * WorldSource.status.latestBundle} and running retention are both separate client calls from the
 * {@link UpdateControl} this method returns for the {@link WorldIngest} itself. If the source
 * update loses a race (409, someone else concurrently wrote its status), this reconciler leaves
 * the ingest's own phase unchanged and reschedules -- not {@code Succeeded} -- so the next
 * reconcile retries the source-side write. Marking the ingest {@code Succeeded} first would be a
 * mistake: {@link #reconcile} treats a terminal-phase ingest as a permanent no-op, so a lost
 * source update after that point would never be retried.
 *
 * <p><b>Retention never deletes a bundle a {@link BlueMapRender} still references.</b> See {@link
 * #applyRetention}.
 */
@ControllerConfiguration
public class WorldIngestReconciler implements Reconciler<WorldIngest> {

    /** Reason set on the {@code Ready} condition while the referenced source does not exist. */
    public static final String SOURCE_NOT_FOUND_REASON = "SourceNotFound";

    /** Reason set on the {@code Ready} condition while another ingest for the same source is active. */
    public static final String CONCURRENT_INGEST_REASON = "ConcurrentIngestActive";

    /** Reason set on the {@code Ready} condition when an existing resource fails the ownership check. */
    public static final String RESOURCE_CONFLICT_REASON = "ResourceConflict";

    /** Reason set once the ingest job has exhausted its retries. */
    public static final String JOB_FAILED_REASON = "JobFailed";

    /** Reason set on the {@code Ready} condition once the ingest job completed. */
    public static final String SUCCEEDED_REASON = "Succeeded";

    private static final String PENDING_PHASE = "Pending";
    private static final String EXTRACTING_PHASE = "Extracting";
    private static final String SUCCEEDED_PHASE = "Succeeded";
    private static final String FAILED_PHASE = "Failed";

    private static final Set<String> TERMINAL_PHASES = Set.of(SUCCEEDED_PHASE, FAILED_PHASE);

    /** Same job->pod label the Kubernetes Job controller always stamps -- see RenderJobBuilder's twin. */
    private static final String JOB_NAME_LABEL = "job-name";

    private static final Duration RECHECK_INTERVAL = Duration.ofSeconds(10);

    private static final int DEFAULT_KEEP_VERSIONS = 5;

    private final KubernetesClient client;
    private final OperatorConfig config;
    private final SourceLockClaimer sourceLockClaimer;
    private final PodLogFetcher podLogFetcher;
    private final BundleStoreFactory bundleStoreFactory;

    public WorldIngestReconciler(KubernetesClient client, OperatorConfig config) {
        this(
                client,
                config,
                source -> client.resources(WorldSource.class)
                        .inNamespace(source.getMetadata().getNamespace())
                        .resource(source)
                        .updateStatus(),
                pod -> fetchPodLog(client, pod),
                destination -> new AwsBundleStore(software.amazon.awssdk.services.s3.S3Client.builder()
                        .region(Region.of(destination.region()))
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                                Secrets.value(
                                        client,
                                        destination.credentialsNamespace(),
                                        destination.credentialsSecretName(),
                                        "AWS_ACCESS_KEY_ID"),
                                Secrets.value(
                                        client,
                                        destination.credentialsNamespace(),
                                        destination.credentialsSecretName(),
                                        "AWS_SECRET_ACCESS_KEY"))))
                        .endpointOverride(java.net.URI.create(destination.endpoint()))
                        .forcePathStyle(true)
                        .build()));
    }

    /** Test seam: fakes for the source-status lock, pod log fetching and the bundle store. */
    WorldIngestReconciler(
            KubernetesClient client,
            OperatorConfig config,
            SourceLockClaimer sourceLockClaimer,
            PodLogFetcher podLogFetcher,
            BundleStoreFactory bundleStoreFactory) {
        this.client = client;
        this.config = config;
        this.sourceLockClaimer = sourceLockClaimer;
        this.podLogFetcher = podLogFetcher;
        this.bundleStoreFactory = bundleStoreFactory;
    }

    @Override
    public UpdateControl<WorldIngest> reconcile(WorldIngest ingest, Context<WorldIngest> context) {
        String currentPhase = ingest.getStatus().getPhase();
        if (currentPhase != null && TERMINAL_PHASES.contains(currentPhase)) {
            return UpdateControl.noUpdate();
        }

        String namespace = ingest.getMetadata().getNamespace();
        String ingestName = ingest.getMetadata().getName();
        String sourceName = ingest.getSpec().getSourceRef().getName();

        WorldSource source = (sourceName == null || sourceName.isBlank())
                ? null
                : client.resources(WorldSource.class).inNamespace(namespace).withName(sourceName).get();
        if (source == null) {
            return pending(ingest, SOURCE_NOT_FOUND_REASON, "source '" + sourceName + "' does not exist");
        }
        if (!ownedBySameSource(ingest.getMetadata().getLabels(), source.getMetadata().getName(), source.getMetadata().getUid())) {
            return conflict(ingest, "WorldSource", sourceName);
        }

        Job existingJob = client.batch().v1().jobs().inNamespace(namespace).withName(ingestName).get();
        if (existingJob != null) {
            if (!ownedBySameIngest(existingJob, ingest)) {
                return conflict(ingest, "Job", ingestName);
            }
            return reconcileActiveJob(ingest, source, existingJob, namespace, ingestName);
        }

        if (anotherActiveIngestJobExists(namespace, sourceName, ingestName)) {
            return pending(
                    ingest,
                    CONCURRENT_INGEST_REASON,
                    "another ingest for source '" + sourceName + "' is already active");
        }

        if (!tryClaimSource(source, namespace, ingestName)) {
            return pending(
                    ingest,
                    CONCURRENT_INGEST_REASON,
                    "another ingest for source '" + sourceName + "' is already active");
        }

        Job job = IngestJobBuilder.build(ingest, source, config);
        client.batch().v1().jobs().inNamespace(namespace).resource(job).createOr(NonDeletingOperation::update);

        ingest.getStatus().setJobName(ingestName);
        ingest.getStatus().setPhase(EXTRACTING_PHASE);
        ingest.getStatus().setStartTime(Instant.now().toString());
        Conditions.set(
                ingest.getStatus().getConditions(), Conditions.ready(false, EXTRACTING_PHASE, "ingest job submitted"));
        return UpdateControl.patchStatus(ingest).rescheduleAfter(RECHECK_INTERVAL);
    }

    private UpdateControl<WorldIngest> reconcileActiveJob(
            WorldIngest ingest, WorldSource source, Job job, String namespace, String ingestName) {
        Pod pod = findPod(namespace, ingestName);
        IngestLogProgress progress =
                pod == null ? null : podLogFetcher.fetchLog(pod).map(IngestLogProgress::parse).orElse(null);

        if (isJobSucceeded(job)) {
            return onJobSucceeded(ingest, source, namespace, progress);
        }
        if (isJobFailed(job)) {
            ingest.getStatus().setPhase(FAILED_PHASE);
            ingest.getStatus().setCompletionTime(Instant.now().toString());
            Conditions.set(
                    ingest.getStatus().getConditions(),
                    Conditions.ready(false, JOB_FAILED_REASON, "ingest job failed"));
            return UpdateControl.patchStatus(ingest);
        }

        if (progress != null) {
            applyProgress(ingest, progress);
        }
        Conditions.set(
                ingest.getStatus().getConditions(),
                Conditions.ready(false, ingest.getStatus().getPhase(), "ingest job is running"));
        return UpdateControl.patchStatus(ingest).rescheduleAfter(RECHECK_INTERVAL);
    }

    /**
     * Handles a succeeded job: computes the bundle's path/version deterministically (see {@link
     * IngestJobBuilder} class Javadoc), writes {@code WorldSource.status.latestBundle} and runs
     * retention -- both <em>before</em> marking the ingest itself {@code Succeeded}, so a lost
     * race on the source update is retried rather than silently dropped (see class Javadoc).
     */
    private UpdateControl<WorldIngest> onJobSucceeded(
            WorldIngest ingest, WorldSource source, String namespace, IngestLogProgress progress) {
        String tenant = tenantNameForNamespace(namespace);
        String sourceName = source.getMetadata().getName();
        String worldId = ingest.getSpec().getWorldName();
        String version = IngestJobBuilder.bundleVersion(ingest);
        String bundlePath = BundlePath.of(tenant, sourceName, worldId, version);
        List<String> dimensions = progress == null ? List.of() : progress.dimensions();

        BundleRef sourceBundle = source.getStatus().getLatestBundle();
        sourceBundle.setPath(bundlePath);
        sourceBundle.setVersion(version);
        sourceBundle.setDimensions(dimensions);

        try {
            sourceLockClaimer.updateStatus(source);
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                // Someone else wrote WorldSource.status concurrently (e.g. a fresh poll cycle
                // updating lastPollTime). Do not mark this ingest terminal yet -- retry the
                // whole success path, including this write, on the next reconcile.
                return UpdateControl.<WorldIngest>noUpdate().rescheduleAfter(RECHECK_INTERVAL);
            }
            throw e;
        }

        applyRetention(source, tenant, sourceName, worldId, bundlePath);

        BundleRef ingestBundle = ingest.getStatus().getBundle();
        ingestBundle.setPath(bundlePath);
        ingestBundle.setVersion(version);
        ingestBundle.setDimensions(dimensions);
        ingest.getStatus().setPhase(SUCCEEDED_PHASE);
        ingest.getStatus().setCompletionTime(Instant.now().toString());
        Conditions.set(
                ingest.getStatus().getConditions(),
                Conditions.ready(true, SUCCEEDED_REASON, "ingest completed, bundle at " + bundlePath));
        return UpdateControl.patchStatus(ingest);
    }

    /**
     * Deletes bundle versions beyond {@code WorldSource.spec.retention.keepVersions}, oldest
     * first, but <b>never one still referenced by a {@link BlueMapRender}</b> -- see the class
     * Javadoc. A version currently blocked from deletion for that reason is simply skipped; it
     * is reconsidered on the next successful ingest's retention pass, once whatever render
     * referenced it has moved on or been deleted.
     *
     * <p>Best-effort: any failure listing/deleting bundle versions is swallowed rather than
     * failing the whole reconciliation -- the ingest itself already succeeded and its own bundle
     * is safe; a bucket temporarily unreachable for pruning is not a reason to leave the ingest
     * stuck retrying forever.
     *
     * <p><b>Never deletes any {@link WorldSource}'s {@code status.latestBundle}, not just this
     * source's own.</b> Bundle listing/deletion is scoped to this source's own prefix ({@link
     * BundlePath}, keyed by {@code sourceName}), so a different source's bundles are not even
     * visible to this pass -- but that scoping is exactly the property a future change to the
     * path scheme could accidentally weaken, and a stray/legacy object under this prefix is not
     * inherently impossible either. Checking every source's recorded {@code latestBundle} here
     * costs one cheap list call and closes that risk regardless of whether the scoping itself
     * stays intact.
     */
    private void applyRetention(
            WorldSource source, String tenant, String sourceName, String worldId, String justWrittenBundlePath) {
        int keepVersions = source.getSpec().getRetention().getKeepVersions();
        if (keepVersions <= 0) {
            keepVersions = DEFAULT_KEEP_VERSIONS;
        }
        try {
            BundleStore store = bundleStoreFactory.create(new BundleDestination(
                    config.bundleS3Region(), config.bundleS3Endpoint(), source.getMetadata().getNamespace(),
                    config.bundleCredentialsSecretName()));
            List<BundleStore.BundleVersion> versions = new java.util.ArrayList<>(
                    store.listVersions(tenant, sourceName, worldId, config.bundleBucket()));
            versions.sort(java.util.Comparator.comparing(BundleStore.BundleVersion::lastModified).reversed());

            String namespace = source.getMetadata().getNamespace();
            for (int i = keepVersions; i < versions.size(); i++) {
                String version = versions.get(i).version();
                String versionPath = BundlePath.of(tenant, sourceName, worldId, version);
                if (versionPath.equals(justWrittenBundlePath)) {
                    continue; // never prune the version this very run just wrote
                }
                if (isReferencedByAnyRender(namespace, versionPath)) {
                    continue;
                }
                if (isLatestBundleOfAnySource(namespace, versionPath)) {
                    continue;
                }
                store.deleteVersion(tenant, sourceName, worldId, version, config.bundleBucket());
            }
        } catch (RuntimeException e) {
            // See method Javadoc: pruning failures must not block the ingest run itself.
        }
    }

    /**
     * Whether {@code versionPath} is the recorded {@code status.latestBundle.path} of <em>any</em>
     * {@link WorldSource} in {@code namespace} -- not just the one this retention pass is running
     * for. See {@link #applyRetention}'s Javadoc for why this check exists alongside the
     * source-scoped path prefix rather than relying on that scoping alone.
     */
    private boolean isLatestBundleOfAnySource(String namespace, String versionPath) {
        List<WorldSource> sources =
                client.resources(WorldSource.class).inNamespace(namespace).list().getItems();
        for (WorldSource candidate : sources) {
            String latestPath = candidate.getStatus().getLatestBundle().getPath();
            if (versionPath.equals(latestPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether any {@link BlueMapRender} in {@code namespace} references bundle version {@code
     * versionPath} (the {@code <tenant>/<sourceName>/<worldId>/<version>} root), regardless of
     * that render's own phase. Deliberately not limited to "currently active" renders: a bundle a
     * <em>completed</em> render still names in its own spec is history worth keeping intact --
     * over-retaining a bundle costs storage, deleting one a render still names is unrecoverable
     * data loss (see the task brief's own framing of this exact risk).
     */
    private boolean isReferencedByAnyRender(String namespace, String versionPath) {
        List<BlueMapRender> renders =
                client.resources(BlueMapRender.class).inNamespace(namespace).list().getItems();
        for (BlueMapRender render : renders) {
            String bundleUrl = render.getSpec().getBundleUrl();
            if (bundleUrl != null && referencesBundle(bundleUrl, versionPath)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Boundary-safe substring check: {@code versionPath} must appear as a full path segment in
     * {@code bundleUrl}, not merely as a prefix of a longer, different version string (e.g.
     * {@code .../v1/...} must not match a URL actually pointing at {@code .../v10/...}).
     */
    static boolean referencesBundle(String bundleUrl, String versionPath) {
        return bundleUrl.contains("/" + versionPath + "/") || bundleUrl.endsWith("/" + versionPath);
    }

    /**
     * Mirrors the ingest pod's fine-grained progress into {@code status} -- <b>except</b> a
     * terminal phase ({@code Succeeded}/{@code Failed}). {@code IngestMain} logs {@code
     * phase=Succeeded} immediately before its process exits, which lands in the pod log strictly
     * before the Kubernetes Job controller observes the pod's exit and updates {@code
     * status.succeeded} -- a reconcile landing in that window would otherwise copy {@code
     * Succeeded} out of the log here, and {@link #reconcile} treats any ingest already in a
     * terminal phase as a permanent no-op on every future reconcile. That would skip {@link
     * #onJobSucceeded} forever: the bundle the job wrote is never registered on {@code
     * WorldSource.status.latestBundle} or {@code WorldIngest.status.bundle}, retention never
     * runs, and nothing retries -- a fully-written bundle that is permanently invisible to the
     * rest of the system. Terminality belongs exclusively to {@link #isJobSucceeded}/{@link
     * #isJobFailed}, evaluated against the Job's own status one line above this method's only
     * caller; this method only ever advances the phase to something non-terminal, and only ever
     * updates progress numbers.
     */
    private static void applyProgress(WorldIngest ingest, IngestLogProgress progress) {
        if (progress.phase() != null && !TERMINAL_PHASES.contains(progress.phase())) {
            ingest.getStatus().setPhase(progress.phase());
        }
        var status = ingest.getStatus().getProgress();
        if (progress.percent() != null) {
            status.setPercent(progress.percent());
        }
        if (progress.bytesDone() != null) {
            status.setBytesDone(progress.bytesDone());
        }
        if (progress.bytesTotal() != null) {
            status.setBytesTotal(progress.bytesTotal());
        }
    }

    private boolean anotherActiveIngestJobExists(String namespace, String sourceName, String excludeIngestName) {
        List<Job> jobs = client.batch()
                .v1()
                .jobs()
                .inNamespace(namespace)
                .withLabel(Labels.SOURCE, sourceName)
                .list()
                .getItems();
        for (Job job : jobs) {
            if (excludeIngestName.equals(job.getMetadata().getName())) {
                continue;
            }
            if (!isJobSucceeded(job) && !isJobFailed(job)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Attempts to claim {@code source.status.activeIngest} for {@code ingestName}. Mirrors
     * {@code BlueMapRenderReconciler.tryClaimMap} exactly -- see its Javadoc for the three
     * "claimed, proceed" outcomes and the 409-means-lost-the-race handling.
     */
    private boolean tryClaimSource(WorldSource source, String namespace, String ingestName) {
        String recordedName = source.getStatus().getActiveIngest().getName();
        if (ingestName.equals(recordedName)) {
            return true;
        }
        if (recordedName != null && !recordedName.isBlank() && isSourceIngestStillActive(namespace, recordedName)) {
            return false;
        }

        source.getStatus().getActiveIngest().setName(ingestName);
        source.getStatus().getActiveIngest().setPhase(EXTRACTING_PHASE);
        try {
            sourceLockClaimer.updateStatus(source);
            return true;
        } catch (KubernetesClientException e) {
            if (e.getCode() == 409) {
                return false;
            }
            throw e;
        }
    }

    private boolean isSourceIngestStillActive(String namespace, String ingestName) {
        WorldIngest recorded =
                client.resources(WorldIngest.class).inNamespace(namespace).withName(ingestName).get();
        if (recorded == null) {
            return false;
        }
        String phase = recorded.getStatus().getPhase();
        return phase == null || !TERMINAL_PHASES.contains(phase);
    }

    /**
     * Best-effort log fetch for the default {@link PodLogFetcher}: a pod that has not started
     * yet, or one whose logs are momentarily unreachable, must not fail reconciliation -- exactly
     * the same tolerance {@code BlueMapRenderReconciler.HttpProgressFetcher} applies to its own
     * best-effort progress source.
     */
    private static Optional<String> fetchPodLog(KubernetesClient client, Pod pod) {
        try {
            String log = client.pods()
                    .inNamespace(pod.getMetadata().getNamespace())
                    .withName(pod.getMetadata().getName())
                    .getLog();
            return Optional.ofNullable(log);
        } catch (KubernetesClientException e) {
            return Optional.empty();
        }
    }

    private Pod findPod(String namespace, String ingestName) {
        List<Pod> pods = client.pods()
                .inNamespace(namespace)
                .withLabel(JOB_NAME_LABEL, ingestName)
                .list()
                .getItems();
        return pods.isEmpty() ? null : pods.get(0);
    }

    private static boolean isJobSucceeded(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return false;
        }
        return (status.getSucceeded() != null && status.getSucceeded() > 0) || hasCondition(status, "Complete");
    }

    /**
     * A Job is terminally failed only once its {@code Failed} condition is set -- which the
     * Kubernetes Job controller does exactly once, after {@code backoffLimit} retries are
     * exhausted. {@code status.failed} (the count of failed pod attempts so far) is deliberately
     * <b>not</b> consulted here: {@code backoffLimit} exists precisely so a single transient pod
     * failure gets retried, not treated as the whole Job's outcome. Counting pod attempts would
     * make this method return {@code true} after the very first failed attempt while the Job
     * controller is still going to retry -- {@link #reconcile} would mark the {@link WorldIngest}
     * terminally {@code Failed} immediately, yet the Job keeps running underneath it and can still
     * write a complete bundle on a later attempt that then has nowhere to be registered, since a
     * terminal ingest is never reconciled again.
     */
    private static boolean isJobFailed(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return false;
        }
        return hasCondition(status, "Failed");
    }

    private static boolean hasCondition(JobStatus status, String type) {
        List<JobCondition> conditions = status.getConditions();
        if (conditions == null) {
            return false;
        }
        return conditions.stream().anyMatch(c -> type.equals(c.getType()) && "True".equals(c.getStatus()));
    }

    /**
     * Checks that {@code ingest}'s own labels name {@code source} by both name <em>and</em> UID --
     * the same owner-check pattern {@code WorldSourceReconciler.ownedBySameSource} applies in the
     * opposite direction (there, checking a {@link WorldIngest} it is about to treat as
     * already-triggered; here, checking the {@link WorldIngest} being reconciled itself).
     *
     * <p>Without this, resolving {@code source} by name alone would let any {@link WorldIngest} --
     * hand-written, or stale after its original source was deleted and a same-named-but-different
     * source (a different UID) was created in its place -- read and overwrite that source's
     * status and, worse, drive {@link #applyRetention} to delete its bundles. {@code
     * WorldSourceReconciler} always stamps {@link Labels#SOURCE}/{@link Labels#SOURCE_UID} on
     * every {@link WorldIngest} it creates, so a legitimately-triggered ingest always passes this
     * check; one that does not is, by construction, not something this reconciler created the
     * lock/retention trust relationship for.
     */
    private static boolean ownedBySameSource(Map<String, String> labels, String sourceName, String sourceUid) {
        if (labels == null || sourceUid == null) {
            return false;
        }
        return Objects.equals(sourceName, labels.get(Labels.SOURCE)) && Objects.equals(sourceUid, labels.get(Labels.SOURCE_UID));
    }

    private static boolean ownedBySameIngest(Job job, WorldIngest ingest) {
        String ingestUid = ingest.getMetadata().getUid();
        if (ingestUid == null) {
            return false;
        }
        List<OwnerReference> owners = job.getMetadata().getOwnerReferences();
        if (owners == null) {
            return false;
        }
        return owners.stream()
                .anyMatch(ref -> "WorldIngest".equals(ref.getKind())
                        && Objects.equals(ingest.getMetadata().getName(), ref.getName())
                        && Objects.equals(ingestUid, ref.getUid()));
    }

    /** See {@code BlueMapMapReconciler.cephUserForNamespace}/{@code IngestJobBuilder}'s identical inversion. */
    private static String tenantNameForNamespace(String namespace) {
        String prefix = "bluemap-";
        return namespace != null && namespace.startsWith(prefix) ? namespace.substring(prefix.length()) : namespace;
    }

    private static UpdateControl<WorldIngest> pending(WorldIngest ingest, String reason, String message) {
        ingest.getStatus().setPhase(PENDING_PHASE);
        Conditions.set(ingest.getStatus().getConditions(), Conditions.ready(false, reason, message));
        return UpdateControl.patchStatus(ingest).rescheduleAfter(RECHECK_INTERVAL);
    }

    private static UpdateControl<WorldIngest> conflict(WorldIngest ingest, String resourceKind, String resourceName) {
        ingest.getStatus().setPhase(PENDING_PHASE);
        Conditions.set(
                ingest.getStatus().getConditions(),
                Conditions.ready(
                        false,
                        RESOURCE_CONFLICT_REASON,
                        "existing " + resourceKind + " '" + resourceName
                                + "' is not owned by this ingest; refusing to adopt it"));
        return UpdateControl.patchStatus(ingest).rescheduleAfter(RECHECK_INTERVAL);
    }

    /**
     * Performs the optimistic {@code updateStatus()} call {@link #tryClaimSource} and {@link
     * #onJobSucceeded} rely on. Exists as a test seam for the same reason {@code
     * BlueMapRenderReconciler.MapLockClaimer} does -- the fabric8 mock server used in tests does
     * not enforce optimistic concurrency, so a real 409 Conflict cannot be reproduced against it.
     */
    @FunctionalInterface
    interface SourceLockClaimer {
        void updateStatus(WorldSource source);
    }

    /** Fetches a pod's full log text, if reachable. */
    @FunctionalInterface
    interface PodLogFetcher {
        Optional<String> fetchLog(Pod pod);
    }

    /** Connection details {@link BundleStoreFactory} needs to build a real {@link BundleStore}. */
    record BundleDestination(String region, String endpoint, String credentialsNamespace, String credentialsSecretName) {}

    /** Builds a {@link BundleStore} for the bundle destination -- a test seam for a fake, in-memory store. */
    @FunctionalInterface
    interface BundleStoreFactory {
        BundleStore create(BundleDestination destination);
    }
}
