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
import net.onelitefeather.apus.operator.api.WorldIngest;
import net.onelitefeather.apus.operator.api.WorldSource;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class WorldIngestReconcilerTest {

    KubernetesClient client;

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
        public List<BundleVersion> listVersions(String tenant, String worldId, String bundleBucket) {
            List<BundleVersion> result = new ArrayList<>();
            versions.forEach((version, time) -> result.add(new BundleVersion(version, time)));
            return result;
        }

        @Override
        public void deleteVersion(String tenant, String worldId, String version, String bundleBucket) {
            versions.remove(version);
            deleted.add(version);
        }
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

    // --- Job submission -------------------------------------------------------------------

    @Test
    void submitsAnIngestJobAndClaimsTheSourceLock() {
        WorldSource source = source("survival-source");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        WorldIngest ingest = ingest("ingest-1");

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
        WorldSource source = source("survival-source");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();

        WorldIngest first = ingest("ingest-1");
        reconciler(new FakeBundleStore()).reconcile(first, null);

        WorldIngest second = ingest("ingest-2");
        reconciler(new FakeBundleStore()).reconcile(second, null);

        assertEquals("Pending", second.getStatus().getPhase());
        assertNull(client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-2").get());
    }

    @Test
    void aSecondIngestProceedsOnceTheFirstIsTerminal() {
        WorldSource source = source("survival-source");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();

        WorldIngest first = ingest("ingest-1");
        reconciler(new FakeBundleStore()).reconcile(first, null);
        // Simulate the first ingest's job failing terminally -- anotherActiveIngestJobExists()
        // checks the Job's own status, exactly like BlueMapRenderReconciler's twin does for
        // renders, so the underlying Job (not just the WorldIngest CR) must reflect failure.
        Job firstJob = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        firstJob.setStatus(new JobStatusBuilder().withFailed(1).build());
        client.batch()
                .v1()
                .jobs()
                .inNamespace("bluemap-friends")
                .resource(firstJob)
                .createOr(io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);
        first.getStatus().setPhase("Failed");
        client.resources(WorldIngest.class).inNamespace("bluemap-friends").resource(first).create();

        WorldIngest second = ingest("ingest-2");
        reconciler(new FakeBundleStore()).reconcile(second, null);

        assertNotNullJob(client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-2").get());
    }

    @Test
    void reconcilingAnAlreadyOwnedJobDoesNotRecreateIt() {
        WorldSource source = source("survival-source");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        WorldIngest ingest = ingest("ingest-1");

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
        WorldSource source = source("survival-source");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();

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

        WorldIngest ingest = ingest("ingest-1");
        reconciler(new FakeBundleStore()).reconcile(ingest, null);

        assertEquals(WorldIngestReconciler.RESOURCE_CONFLICT_REASON, readyReason(ingest));
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
        WorldSource source = source("survival-source");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        WorldIngest ingest = ingest("ingest-1");
        WorldIngestReconciler reconciler = reconciler(new FakeBundleStore());
        reconciler.reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withFailed(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        reconciler.reconcile(ingest, null);

        assertEquals("Failed", ingest.getStatus().getPhase());
        assertEquals(WorldIngestReconciler.JOB_FAILED_REASON, readyReason(ingest));
    }

    @Test
    void aSucceededJobMarksTheIngestSucceededAndFillsTheBundleRef() {
        WorldSource source = source("survival-source");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        WorldIngest ingest = ingest("ingest-1");
        WorldIngestReconciler reconciler = reconciler(new FakeBundleStore());
        reconciler.reconcile(ingest, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("ingest-1").get();
        job.setStatus(new JobStatusBuilder().withSucceeded(1).build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).createOr(
                io.fabric8.kubernetes.client.dsl.NonDeletingOperation::update);

        reconciler.reconcile(ingest, null);

        assertEquals("Succeeded", ingest.getStatus().getPhase());
        assertEquals("friends/world/ingest-1", ingest.getStatus().getBundle().getPath());
        assertEquals("ingest-1", ingest.getStatus().getBundle().getVersion());

        WorldSource updated =
                client.resources(WorldSource.class).inNamespace("bluemap-friends").withName("survival-source").get();
        assertEquals("friends/world/ingest-1", updated.getStatus().getLatestBundle().getPath());
    }

    @Test
    void aSucceededJobFillsDimensionsParsedFromThePodLog() {
        WorldSource source = source("survival-source");
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();
        WorldIngest ingest = ingest("ingest-1");
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

    // --- Retention --------------------------------------------------------------------------

    @Test
    void retentionDeletesOlderVersionsBeyondKeepVersions() {
        WorldSource source = source("survival-source");
        source.getSpec().getRetention().setKeepVersions(2);
        client.resources(WorldSource.class).inNamespace("bluemap-friends").resource(source).create();

        FakeBundleStore store = new FakeBundleStore();
        store.seed("old-1", Instant.parse("2026-08-01T00:00:00Z"));
        store.seed("old-2", Instant.parse("2026-08-02T00:00:00Z"));
        store.seed("old-3", Instant.parse("2026-08-03T00:00:00Z"));

        WorldIngest ingest = ingest("ingest-1"); // this run's own version becomes the newest
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

        FakeBundleStore store = new FakeBundleStore();
        store.seed("referenced-version", Instant.parse("2026-08-01T00:00:00Z"));

        BlueMapRender render = new BlueMapRender();
        render.setMetadata(
                new ObjectMetaBuilder().withName("r1").withNamespace("bluemap-friends").build());
        render.getSpec().setBundleUrl("s3://bundles/friends/world/referenced-version/dimensions/overworld/region");
        client.resources(BlueMapRender.class).inNamespace("bluemap-friends").resource(render).create();

        WorldIngest ingest = ingest("ingest-1");
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

        WorldIngest ingest = ingest("ingest-1");
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
