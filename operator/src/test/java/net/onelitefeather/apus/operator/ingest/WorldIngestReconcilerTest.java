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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.WorldIngest;
import net.onelitefeather.apus.operator.api.WorldSource;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class WorldIngestReconcilerTest {

    KubernetesClient client;

    private WorldSource source(String name) {
        WorldSource source = new WorldSource();
        source.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        source.getSpec().setType("s3");
        source.getSpec().getS3().setBucket("backups");
        return source;
    }

    /**
     * Creates {@code name} via the (mock) API server and returns the server-fetched copy --
     * needed because the fabric8 CRUD mock server assigns its own UID on create, ignoring
     * whatever the client supplied (exactly like a real API server would). {@link #ingest(String,
     * WorldSource)} needs the *actual* UID to stamp owner labels {@code WorldIngestReconciler}'s
     * ownership check will accept, so every test that creates a source goes through here rather
     * than the raw {@link #source(String)} builder plus a bare {@code .create()}.
     */
    private WorldSource createSource(String name) {
        WorldSource source = source(name);
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        return client.resources(WorldSource.class).inNamespace("bluemap-friends").withName(name).get();
    }

    /** An ingest whose {@code sourceRef} points at a source that was never created. */
    private WorldIngest ingest(String name) {
        WorldIngest ingest = new WorldIngest();
        ingest.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        ingest.getSpec().getSourceRef().setName("survival-source");
        ingest.getSpec().setSourceVersion("v1.zip");
        ingest.getSpec().setWorldName("world");
        return ingest;
    }

    /**
     * An ingest legitimately triggered for {@code source} -- carries the exact owner labels
     * {@code WorldSourceReconciler} stamps (name and UID), which {@code WorldIngestReconciler}'s
     * ownership check requires before it will act on the source at all.
     */
    private WorldIngest ingest(String name, WorldSource source) {
        WorldIngest ingest = new WorldIngest();
        ingest.setMetadata(new ObjectMetaBuilder()
                .withName(name)
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .addToLabels(Labels.SOURCE, source.getMetadata().getName())
                .addToLabels(Labels.SOURCE_UID, source.getMetadata().getUid())
                .build());
        ingest.getSpec().getSourceRef().setName(source.getMetadata().getName());
        ingest.getSpec().setSourceVersion("v1.zip");
        ingest.getSpec().setWorldName("world");
        return ingest;
    }

    private String readyReason(WorldIngest ingest) {
        return ingest.getStatus().getConditions().stream()
                .filter(c -> Conditions.READY.equals(c.getType()))
                .findFirst()
                .orElseThrow()
                .getReason();
    }

    /** A fake {@link BundleStore} the tests can inspect/pre-seed without touching real S3. */
    private static final class FakeBundleStore implements BundleStore {
        final Map<String, Instant> versions = new HashMap<>();
        final List<String> deleted = new ArrayList<>();

        void seed(String version, Instant lastModified) {
            versions.put(version, lastModified);
        }

        @Override
        public List<BundleVersion> listVersions(String tenant, String sourceName, String worldId, String bundleBucket) {
            List<BundleVersion> result = new ArrayList<>();
            versions.forEach((version, time) -> result.add(new BundleVersion(version, time)));
            return result;
        }

        @Override
        public void deleteVersion(String tenant, String sourceName, String worldId, String version, String bundleBucket) {
            versions.remove(version);
            deleted.add(version);
        }
    }

    /** A Job status with a {@code Failed} condition -- what the Job controller sets once {@code backoffLimit} is exhausted. */
    private static JobStatus failedJobStatus() {
        return new JobStatusBuilder()
                .withConditions(new JobConditionBuilder()
                        .withType("Failed")
                        .withStatus("True")
                        .build())
                .build();
    }

    private WorldIngestReconciler reconciler(FakeBundleStore store) {
        return new WorldIngestReconciler(
                client,
                OperatorConfig.defaults(),
                src -> client.resources(WorldSource.class)
                        .inNamespace(src.getMetadata().getNamespace())
                        .resource(src)
                        .updateStatus(),
                pod -> Optional.empty(),
                destination -> store);
    }

    private WorldIngestReconciler reconcilerWithLog(FakeBundleStore store, String log) {
        return new WorldIngestReconciler(
                client,
                OperatorConfig.defaults(),
                src -> client.resources(WorldSource.class)
                        .inNamespace(src.getMetadata().getNamespace())
                        .resource(src)
                        .updateStatus(),
                pod -> Optional.of(log),
                destination -> store);
    }

    private void markJobSucceeded(String jobName) {
        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName(jobName).get();
        job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
        client.batch()
                .v1()
                .jobs()
                .inNamespace("bluemap-friends")
                .resource(job)
                .createOr(io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);
    }

    // --- Job submission -------------------------------------------------------------------

    @Test
    void submitsAnIngestJobAndClaimsTheSourceLock() {
        WorldSource source = createSource("survival-source");
        WorldIngest ingest = ingest("ingest-1", source);

        reconciler(new FakeBundleStore()).reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        assertNotNullJob(job);
        assertEquals("Extracting", ingest.getStatus().getPhase());

        WorldSource updated =
                client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("survival-source").get();
        assertEquals("ingest-1", updated.getStatus().getActiveIngest().getName());
    }

    private static void assertNotNullJob(Job job) {
        if (job == null) {
            throw new AssertionError("expected the ingest job to have been created");
        }
    }

    @Test
    void refusesToStartASecondIngestForTheSameSource() {
        WorldSource source = createSource("survival-source");

        WorldIngest first = ingest("ingest-1", source);
        reconciler(new FakeBundleStore()).reconcile(first, null);

        WorldIngest second = ingest("ingest-2", source);
        reconciler(new FakeBundleStore()).reconcile(second, null);

        assertEquals("Pending", second.getStatus().getPhase());
        assertNull(client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-2").get());
    }

    @Test
    void aSecondIngestProceedsOnceTheFirstIsTerminal() {
        WorldSource source = createSource("survival-source");

        WorldIngest first = ingest("ingest-1", source);
        reconciler(new FakeBundleStore()).reconcile(first, null);
        // Simulate the first ingest's job failing terminally -- anotherActiveIngestJobExists()
        // checks the Job's own status, exactly like BlueMapRenderReconciler's twin does for
        // renders, so the underlying Job (not just the WorldIngest CR) must reflect failure.
        Job firstJob = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        firstJob.setStatus(failedJobStatus());
        client.batch()
                .v1()
                .jobs()
                .inNamespace("bluemap-friends")
                .resource(firstJob)
                .createOr(io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);
        first.getStatus().setPhase("Failed");
        client.resources(WorldIngest.class).inNamespace("bluemap-friends").resource(first).create();

        WorldIngest second = ingest("ingest-2", source);
        reconciler(new FakeBundleStore()).reconcile(second, null);

        assertNotNullJob(client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-2").get());
    }

    @Test
    void reconcilingAnAlreadyOwnedJobDoesNotRecreateIt() {
        WorldSource source = createSource("survival-source");
        WorldIngest ingest = ingest("ingest-1", source);

        WorldIngestReconciler reconciler = reconciler(new FakeBundleStore());
        reconciler.reconcile(ingest, null);
        String firstResourceVersion = client.batch()
                .v1()
                .jobs()
                .inNamespace("bluemap-friends")
                .withName("ingest-1")
                .get()
                .getMetadata()
                .getResourceVersion();

        reconciler.reconcile(ingest, null);
        String secondResourceVersion = client.batch()
                .v1()
                .jobs()
                .inNamespace("bluemap-friends")
                .withName("ingest-1")
                .get()
                .getMetadata()
                .getResourceVersion();

        assertEquals(firstResourceVersion, secondResourceVersion, "reconciling twice must be idempotent");
    }

    @Test
    void sourceNotFoundIsReportedInsteadOfThrowing() {
        WorldIngest ingest = ingest("ingest-1"); // sourceRef points at a source never created

        reconciler(new FakeBundleStore()).reconcile(ingest, null);

        assertEquals(WorldIngestReconciler.SOURCE_NOT_FOUND_REASON, readyReason(ingest));
    }

    @Test
    void aJobNotOwnedByThisIngestIsReportedAsAConflict() {
        WorldSource source = createSource("survival-source");

        Job foreignJob = new JobBuilder()
                .withNewMetadata()
                .withName("ingest-1")
                .withNamespace("bluemap-friends")
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .withRestartPolicy("Never")
                .endSpec()
                .endTemplate()
                .endSpec()
                .build(); // no owner reference at all
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(foreignJob).create();

        WorldIngest ingest = ingest("ingest-1", source);
        reconciler(new FakeBundleStore()).reconcile(ingest, null);

        assertEquals(WorldIngestReconciler.RESOURCE_CONFLICT_REASON, readyReason(ingest));
    }

    /**
     * S3: {@code WorldIngestReconciler} must not trust {@code spec.sourceRef.name} alone -- a
     * hand-written or stale {@link WorldIngest} (e.g. its original source was deleted and a
     * different source created under the same name, giving it a different UID) must not be able
     * to read/overwrite that source's status or drive retention against its bundles. Only an
     * ingest carrying the exact owner labels {@code WorldSourceReconciler} stamps (name AND UID)
     * may proceed.
     */
    @Test
    void anIngestWithoutTheSourceOwnerLabelsIsReportedAsAConflictAndNeverTouchesTheSource() {
        createSource("survival-source");

        // Same sourceRef.name as a legitimately-triggered ingest, but no owner labels at all --
        // exactly what a hand-written WorldIngest (or one whose original source was deleted and
        // recreated under the same name, giving it a different UID) would look like.
        WorldIngest rogue = new WorldIngest();
        rogue.setMetadata(new ObjectMetaBuilder()
                .withName("rogue-ingest")
                .withNamespace("bluemap-friends")
                .withUid(UUID.randomUUID().toString())
                .build());
        rogue.getSpec().getSourceRef().setName("survival-source");
        rogue.getSpec().setSourceVersion("v1.zip");
        rogue.getSpec().setWorldName("world");

        reconciler(new FakeBundleStore()).reconcile(rogue, null);

        assertEquals(WorldIngestReconciler.RESOURCE_CONFLICT_REASON, readyReason(rogue));
        assertNull(
                client.batch().v1().jobs().inNamespace("bluemap-friends").withName("rogue-ingest").get(),
                "no job may be submitted for an ingest that fails the source ownership check");

        WorldSource unchanged =
                client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("survival-source").get();
        assertNull(
                unchanged.getStatus().getActiveIngest().getName(),
                "the source's status must be untouched by an ingest that does not own it");
    }

    @Test
    void reconcilingATerminalIngestIsANoOp() {
        WorldIngest ingest = ingest("ingest-1");
        ingest.getStatus().setPhase("Succeeded");

        var control = reconciler(new FakeBundleStore()).reconcile(ingest, null);

        assertTrue(control.isNoUpdate());
    }

    // --- Job progress / completion --------------------------------------------------------

    @Test
    void aFailedJobMarksTheIngestFailed() {
        WorldSource source = createSource("survival-source");
        WorldIngest ingest = ingest("ingest-1", source);
        WorldIngestReconciler reconciler = reconciler(new FakeBundleStore());
        reconciler.reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(failedJobStatus());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        reconciler.reconcile(ingest, null);

        assertEquals("Failed", ingest.getStatus().getPhase());
        assertEquals(WorldIngestReconciler.JOB_FAILED_REASON, readyReason(ingest));
    }

    /**
     * B2: {@code backoffLimit} exists precisely so a transient pod failure gets retried -- a
     * single failed *pod attempt* ({@code status.failed > 0}, no {@code Failed} condition yet)
     * must not end the ingest terminally while the Job controller is still going to retry it. If
     * it did, the CR would go terminal here, the Job would keep running underneath it, and a
     * later attempt could still write a complete bundle that then has nowhere to be registered --
     * a terminal ingest is never reconciled again.
     */
    @Test
    void aSingleFailedPodAttemptDoesNotEndTheIngestWhileTheJobStillHasRetriesLeft() {
        WorldSource source = createSource("survival-source");
        WorldIngest ingest = ingest("ingest-1", source);
        WorldIngestReconciler reconciler = reconciler(new FakeBundleStore());
        reconciler.reconcile(ingest, null);

        // One failed pod attempt recorded, but the Job controller has not (yet) exhausted
        // backoffLimit -- no Failed condition set. This is exactly the state a Job is in
        // between a transient pod crash and its next retry attempt.
        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withFailed(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        reconciler.reconcile(ingest, null);

        assertFalse("Failed".equals(ingest.getStatus().getPhase()), "a single failed attempt must not be terminal");
        assertEquals("Extracting", ingest.getStatus().getPhase(), "the ingest must still be treated as running");
    }

    @Test
    void aSucceededJobMarksTheIngestSucceededAndFillsTheBundleRef() {
        WorldSource source = createSource("survival-source");
        WorldIngest ingest = ingest("ingest-1", source);
        WorldIngestReconciler reconciler = reconciler(new FakeBundleStore());
        reconciler.reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        reconciler.reconcile(ingest, null);

        assertEquals("Succeeded", ingest.getStatus().getPhase());
        assertEquals("friends/survival-source/world/ingest-1", ingest.getStatus().getBundle().getPath());
        assertEquals("ingest-1", ingest.getStatus().getBundle().getVersion());

        WorldSource updated =
                client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("survival-source").get();
        assertEquals("friends/survival-source/world/ingest-1", updated.getStatus().getLatestBundle().getPath());
    }

    @Test
    void aSucceededJobFillsDimensionsParsedFromThePodLog() {
        WorldSource source = createSource("survival-source");
        WorldIngest ingest = ingest("ingest-1", source);
        String log = "[apus-ingest] detected layout kind=bukkit dimensions=[overworld, the_nether]";
        WorldIngestReconciler reconciler = reconcilerWithLog(new FakeBundleStore(), log);
        reconciler.reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);
        // findPod() locates the pod by the "job-name" label the Job controller always stamps.
        io.fabric8.kubernetes.api.model.Pod pod = new io.fabric8.kubernetes.api.model.PodBuilder()
                .withNewMetadata()
                .withName("ingest-1-abcde")
                .withNamespace("bluemap-friends")
                .addToLabels("job-name", "ingest-1")
                .endMetadata()
                .build();
        client.pods().inNamespace("bluemap-friends").resource(pod).create();

        reconciler.reconcile(ingest, null);

        assertEquals("Succeeded", ingest.getStatus().getPhase());
        assertEquals(List.of("overworld", "the_nether"), ingest.getStatus().getBundle().getDimensions());
    }

    /**
     * C1: {@code IngestMain} logs {@code phase=Succeeded} strictly before the process exits --
     * i.e. strictly before the Kubernetes Job controller can have observed the pod's exit and set
     * {@code status.succeeded}. A reconcile landing in exactly that window must not adopt
     * {@code Succeeded} out of the log: terminality belongs only to the Job's own status. Without
     * the fix, this reconcile would set the ingest's phase terminal here, {@link
     * WorldIngestReconciler#reconcile} would then treat every future reconcile of this ingest as
     * a no-op (see the class Javadoc), and {@code onJobSucceeded} -- which fills {@code
     * status.bundle} and {@code WorldSource.status.latestBundle} -- would never run, even once the
     * Job controller genuinely reports success afterwards.
     */
    @Test
    void logReportingSucceededBeforeTheJobStatusDoesNotEndTheIngestPrematurely() {
        WorldSource source = createSource("survival-source");
        WorldIngest ingest = ingest("ingest-1", source);
        // The exact race: IngestMain's last log line already says Succeeded...
        String log = "[apus-ingest] phase=Succeeded bundlePath=friends/survival-source/world/ingest-1";
        WorldIngestReconciler reconciler = reconcilerWithLog(new FakeBundleStore(), log);
        reconciler.reconcile(ingest, null); // submits the job

        // ...but the Job controller has not observed the pod's exit yet -- status is still empty.
        io.fabric8.kubernetes.api.model.Pod pod = new io.fabric8.kubernetes.api.model.PodBuilder()
                .withNewMetadata()
                .withName("ingest-1-abcde")
                .withNamespace("bluemap-friends")
                .addToLabels("job-name", "ingest-1")
                .endMetadata()
                .build();
        client.pods().inNamespace("bluemap-friends").resource(pod).create();

        reconciler.reconcile(ingest, null);

        assertFalse(
                "Succeeded".equals(ingest.getStatus().getPhase()),
                "the log alone must never make the ingest terminal -- only the Job's own status may");
        assertNull(
                ingest.getStatus().getBundle().getPath(), "the bundle must not be registered before the job actually succeeded");

        // Now the Job controller catches up for real.
        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        reconciler.reconcile(ingest, null);

        assertEquals("Succeeded", ingest.getStatus().getPhase());
        assertEquals(
                "friends/survival-source/world/ingest-1",
                ingest.getStatus().getBundle().getPath(),
                "the bundle must be registered once the job genuinely succeeded");
        WorldSource updated =
                client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("survival-source").get();
        assertEquals(
                "friends/survival-source/world/ingest-1",
                updated.getStatus().getLatestBundle().getPath(),
                "WorldSource.status.latestBundle must be filled once the job genuinely succeeded");
    }

    // --- Retention --------------------------------------------------------------------------

    @Test
    void retentionDeletesOlderVersionsBeyondKeepVersions() {
        WorldSource source = source("survival-source");
        source.getSpec().getRetention().setKeepVersions(2);
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        source = client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("survival-source").get();

        FakeBundleStore store = new FakeBundleStore();
        store.seed("old-1", Instant.parse("2026-08-01T00:00:00Z"));
        store.seed("old-2", Instant.parse("2026-08-02T00:00:00Z"));
        store.seed("old-3", Instant.parse("2026-08-03T00:00:00Z"));

        WorldIngest ingest = ingest("ingest-1", source); // this run's own version becomes the newest
        WorldIngestReconciler reconciler = reconciler(store);
        reconciler.reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        reconciler.reconcile(ingest, null);

        // keepVersions=2 plus the just-written "ingest-1" version (newest) = 3 kept; the two
        // oldest of the three seeded versions must be gone.
        assertTrue(store.deleted.contains("old-1"));
        assertFalse(store.versions.containsKey("old-1"));
        assertTrue(store.versions.containsKey("old-3"), "the newest of the pre-existing versions must survive");
    }

    @Test
    void retentionNeverDeletesAVersionStillReferencedByABlueMapRender() {
        WorldSource source = source("survival-source");
        source.getSpec().getRetention().setKeepVersions(0); // would prune everything without the guard
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        source = client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("survival-source").get();

        FakeBundleStore store = new FakeBundleStore();
        store.seed("referenced-version", Instant.parse("2026-08-01T00:00:00Z"));

        BlueMapRender render = new BlueMapRender();
        render.setMetadata(
                new ObjectMetaBuilder().withName("r1").withNamespace("bluemap-friends").build());
        render.getSpec()
                .setBundleUrl(
                        "s3://bundles/friends/survival-source/world/referenced-version/dimensions/overworld/region");
        client.resources(BlueMapRender.class).inNamespace("bluemap-friends").resource(render).create();

        WorldIngest ingest = ingest("ingest-1", source);
        WorldIngestReconciler reconciler = reconciler(store);
        reconciler.reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        reconciler.reconcile(ingest, null);

        assertTrue(
                store.versions.containsKey("referenced-version"),
                "a version a BlueMapRender still references must never be deleted, even beyond keepVersions");
        assertFalse(store.deleted.contains("referenced-version"));
    }

    @Test
    void retentionKeepsTheJustWrittenVersionEvenWithKeepVersionsZero() {
        WorldSource source = source("survival-source");
        source.getSpec().getRetention().setKeepVersions(0);
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        source = client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("survival-source").get();

        WorldIngest ingest = ingest("ingest-1", source);
        WorldIngestReconciler reconciler = reconciler(new FakeBundleStore());
        reconciler.reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        // Must not throw even though the FakeBundleStore never had "ingest-1" seeded into it
        // (a real S3 listVersions() call would see it because the job actually wrote it).
        reconciler.reconcile(ingest, null);

        assertEquals("Succeeded", ingest.getStatus().getPhase());
    }

    /**
     * C2 (part 1): the bundle path must be scoped by the owning source's name, not just {@code
     * tenant}/{@code worldId} -- two different {@link WorldSource}s in the same namespace both
     * ingesting a world literally named {@code "world"} (the Minecraft default) must never
     * resolve to the same bundle path.
     */
    @Test
    void twoSourcesWithTheSameWorldNameNeverCollideOnTheSameBundlePath() {
        WorldSource sourceA = createSource("source-a");
        WorldSource sourceB = createSource("source-b");

        WorldIngest ingestA = ingest("ingest-a", sourceA);
        WorldIngest ingestB = ingest("ingest-b", sourceB);
        WorldIngestReconciler reconciler = reconciler(new FakeBundleStore());
        reconciler.reconcile(ingestA, null);
        reconciler.reconcile(ingestB, null);

        markJobSucceeded("ingest-a");
        markJobSucceeded("ingest-b");
        reconciler.reconcile(ingestA, null);
        reconciler.reconcile(ingestB, null);

        assertEquals("friends/source-a/world/ingest-a", ingestA.getStatus().getBundle().getPath());
        assertEquals("friends/source-b/world/ingest-b", ingestB.getStatus().getBundle().getPath());
        assertFalse(
                ingestA.getStatus().getBundle().getPath().equals(ingestB.getStatus().getBundle().getPath()),
                "two different sources ingesting the same world name must never share a bundle path");

        WorldSource updatedA =
                client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("source-a").get();
        WorldSource updatedB =
                client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("source-b").get();
        assertEquals("friends/source-a/world/ingest-a", updatedA.getStatus().getLatestBundle().getPath());
        assertEquals("friends/source-b/world/ingest-b", updatedB.getStatus().getLatestBundle().getPath());
    }

    /**
     * C2 (part 2): retention must never delete a bundle version recorded as <em>any</em> {@link
     * WorldSource}'s {@code status.latestBundle} in the namespace, not just the source the
     * current retention pass belongs to -- a safety net independent of (and in addition to) the
     * source-scoped path prefix from part 1; see {@code applyRetention}'s Javadoc.
     */
    @Test
    void retentionNeverDeletesAVersionRecordedAsAnotherSourcesLatestBundle() {
        WorldSource sourceA = source("source-a");
        sourceA.getSpec().getRetention().setKeepVersions(0); // would prune everything without the guard
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(sourceA).create();
        sourceA = client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("source-a").get();

        WorldSource sourceB = source("source-b");
        sourceB.getStatus().getLatestBundle().setPath("friends/source-a/world/shared-version");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(sourceB).create();

        FakeBundleStore store = new FakeBundleStore();
        store.seed("shared-version", Instant.parse("2026-08-01T00:00:00Z"));

        WorldIngest ingest = ingest("ingest-1", sourceA);
        WorldIngestReconciler reconciler = reconciler(store);
        reconciler.reconcile(ingest, null);
        markJobSucceeded("ingest-1");
        reconciler.reconcile(ingest, null);

        assertTrue(
                store.versions.containsKey("shared-version"),
                "a version recorded as another source's latestBundle must never be deleted");
        assertFalse(store.deleted.contains("shared-version"));
    }

    // --- referencesBundle boundary safety ---------------------------------------------------

    @Test
    void referencesBundleDoesNotMatchAVersionThatIsOnlyAPrefixOfAnother() {
        assertFalse(WorldIngestReconciler.referencesBundle("s3://bundles/t/w/v10/dimensions/overworld", "t/w/v1"));
    }

    @Test
    void referencesBundleMatchesAnExactPathSegment() {
        assertTrue(WorldIngestReconciler.referencesBundle("s3://bundles/t/w/v1/dimensions/overworld", "t/w/v1"));
    }

    @Test
    void referencesBundleMatchesWhenTheVersionIsTheEntireTrailingPath() {
        assertTrue(WorldIngestReconciler.referencesBundle("s3://bundles/t/w/v1", "t/w/v1"));
    }
}
