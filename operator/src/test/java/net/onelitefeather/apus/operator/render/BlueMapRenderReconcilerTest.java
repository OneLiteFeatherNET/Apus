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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ContainerStateBuilder;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStateTerminatedBuilder;
import io.fabric8.kubernetes.api.model.ContainerStatusBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobConditionBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatusBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import net.onelitefeather.apus.operator.api.Conditions;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class BlueMapRenderReconcilerTest {

    KubernetesClient client;

    private BlueMapRender render(String name) {
        BlueMapRender render = new BlueMapRender();
        render.setMetadata(new ObjectMetaBuilder().withName(name).withNamespace("bluemap-friends").build());
        render.getSpec().getMapRef().setName("survival-overworld");
        render.getSpec().setBundleUrl("s3://bundles/w/v1/overworld");
        return render;
    }

    /** Same as {@link #render(String)} but with a UID, as a real API server would assign. */
    private BlueMapRender renderWithUid(String name) {
        BlueMapRender render = render(name);
        render.getMetadata().setUid(UUID.randomUUID().toString());
        return render;
    }

    private BlueMapMap boundMap() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName("survival-overworld")
                .withNamespace("bluemap-friends")
                .build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        map.getSpec().getBluemap().setMinecraftVersion("1.21.10");
        map.getStatus().getBucket().setName("apus-friends-survival-overworld");
        map.getStatus().getBucket().setEndpoint("http://rgw.example.svc:80");
        map.getStatus().getBucket().setSecretName("survival-overworld");
        return map;
    }

    private String readyReason(BlueMapRender render) {
        return render.getStatus().getConditions().stream()
                .filter(condition -> Conditions.READY.equals(condition.getType()))
                .findFirst()
                .orElseThrow()
                .getReason();
    }

    // --- Mandated by the task brief -------------------------------------------------------

    @Test
    void refusesToStartASecondRenderForTheSameMap() {
        // Two writers on the same map storage can leave the map inconsistent,
        // which is why Forbid is the default concurrency policy.
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());

        BlueMapRender first = render("render-1");
        reconciler.reconcile(first, null);

        BlueMapRender second = render("render-2");
        reconciler.reconcile(second, null);

        assertEquals("Pending", second.getStatus().getPhase());
        assertNull(
                client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-2").get(),
                "no second job may be created while the first is active");
    }

    @Test
    void doesNotRetryWhenTheStorageQuotaIsExceeded() {
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = render("render-quota");

        reconciler.onQuotaExceeded(render, "bucket full");

        assertEquals("Failed", render.getStatus().getPhase());
        assertNotNull(
                render.getStatus().getConditions().stream()
                        .filter(c -> "StorageQuotaExceeded".equals(c.getReason()))
                        .findFirst()
                        .orElse(null),
                "a quota failure must be visible as its own condition and must not be retried");
    }

    // --- Additional coverage: the map/bucket gate ------------------------------------------

    @Test
    void refusesToStartARenderWhenTheMapDoesNotExist() {
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = render("render-1");

        UpdateControl<BlueMapRender> control = reconciler.reconcile(render, null);

        assertEquals("Pending", render.getStatus().getPhase());
        assertTrue(control.getScheduleDelay().isPresent());
    }

    @Test
    void refusesToStartARenderWhenTheMapHasNoBoundBucket() {
        client.resources(BlueMapMap.class)
                .inNamespace("bluemap-friends")
                .resource(unboundMap())
                .create();
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = render("render-1");

        reconciler.reconcile(render, null);

        assertEquals("Pending", render.getStatus().getPhase());
        assertNull(client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-1").get());
    }

    private BlueMapMap unboundMap() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName("survival-overworld")
                .withNamespace("bluemap-friends")
                .build());
        return map;
    }

    // --- Real concurrency lock, exercised against a bound map ------------------------------

    @Test
    void createsARenderJobOnceTheMapBucketIsBound() {
        client.resources(BlueMapMap.class).inNamespace("bluemap-friends").resource(boundMap()).create();
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = renderWithUid("render-1");

        UpdateControl<BlueMapRender> control = reconciler.reconcile(render, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-1").get();
        assertNotNull(job, "job must be created once the map's bucket is bound");
        assertEquals("Rendering", render.getStatus().getPhase());
        assertEquals("render-1", render.getStatus().getJobName());
        assertNotNull(render.getStatus().getStartTime());
        assertTrue(control.getScheduleDelay().isPresent(), "an active render must be rechecked later");
    }

    @Test
    void blocksASecondRenderWhileTheFirstJobIsStillActive() {
        client.resources(BlueMapMap.class).inNamespace("bluemap-friends").resource(boundMap()).create();
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        reconciler.reconcile(renderWithUid("render-1"), null);

        BlueMapRender second = renderWithUid("render-2");
        reconciler.reconcile(second, null);

        assertEquals("Pending", second.getStatus().getPhase());
        assertEquals("ConcurrentRenderActive", readyReason(second));
        assertNull(client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-2").get());
    }

    @Test
    void allowsANewRenderOnceThePreviousJobHasFinished() {
        client.resources(BlueMapMap.class).inNamespace("bluemap-friends").resource(boundMap()).create();
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        reconciler.reconcile(renderWithUid("render-1"), null);

        Job firstJob = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-1").get();
        firstJob.setStatus(new JobStatusBuilder()
                .withSucceeded(1)
                .build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(firstJob).updateStatus();

        BlueMapRender second = renderWithUid("render-2");
        reconciler.reconcile(second, null);

        assertNotNull(
                client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-2").get(),
                "a finished render must not block a new one");
    }

    // --- Ownership check, mirroring TenantReconciler ---------------------------------------

    @Test
    void refusesToAdoptAJobNotOwnedByThisRender() {
        client.resources(BlueMapMap.class).inNamespace("bluemap-friends").resource(boundMap()).create();
        Job foreignJob = new JobBuilder()
                .withNewMetadata()
                .withName("render-1")
                .withNamespace("bluemap-friends")
                .endMetadata()
                .withNewSpec()
                .withNewTemplate()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("bluemap")
                .withImage("apus/runner:dev")
                .endContainer()
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(foreignJob).create();
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = renderWithUid("render-1");

        UpdateControl<BlueMapRender> control = reconciler.reconcile(render, null);

        assertTrue(control.isPatchStatus());
        assertEquals(BlueMapRenderReconciler.RESOURCE_CONFLICT_REASON, readyReason(render));
    }

    // --- Terminal Job states propagate into render status -----------------------------------

    @Test
    void transitionsToSucceededWhenItsOwnJobCompletes() {
        client.resources(BlueMapMap.class).inNamespace("bluemap-friends").resource(boundMap()).create();
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = renderWithUid("render-1");
        reconciler.reconcile(render, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-1").get();
        job.setStatus(new JobStatusBuilder()
                .withSucceeded(1)
                .build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).updateStatus();

        UpdateControl<BlueMapRender> control = reconciler.reconcile(render, null);

        assertEquals("Succeeded", render.getStatus().getPhase());
        assertNotNull(render.getStatus().getCompletionTime());
        assertTrue(control.isPatchStatus());
    }

    @Test
    void transitionsToFailedWhenItsOwnJobExhaustsRetries() {
        client.resources(BlueMapMap.class).inNamespace("bluemap-friends").resource(boundMap()).create();
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = renderWithUid("render-1");
        reconciler.reconcile(render, null);

        Job job = client.batch().v1().jobs().inNamespace("bluemap-friends").withName("render-1").get();
        job.setStatus(new JobStatusBuilder()
                .withConditions(new JobConditionBuilder()
                        .withType("Failed")
                        .withStatus("True")
                        .build())
                .build());
        client.batch().v1().jobs().inNamespace("bluemap-friends").resource(job).updateStatus();

        reconciler.reconcile(render, null);

        assertEquals("Failed", render.getStatus().getPhase());
        assertEquals("JobFailed", readyReason(render));
    }

    // --- Progress transfer from the pod ------------------------------------------------------

    @Test
    void transfersProgressFromThePodIntoStatus() {
        client.resources(BlueMapMap.class).inNamespace("bluemap-friends").resource(boundMap()).create();
        String json =
                """
                {"state":"rendering","currentMap":"overworld","progress":0.5,\
                "etaSeconds":42,"queuedTasks":-1,"renderThreads":-1,"degraded":false,\
                "description":"rendering"}""";
        BlueMapRenderReconciler reconciler =
                new BlueMapRenderReconciler(client, OperatorConfig.defaults(), pod -> Optional.of(json));
        BlueMapRender render = renderWithUid("render-1");
        reconciler.reconcile(render, null);
        createPodForJob("render-1", terminatedContainer(null, null));

        reconciler.reconcile(render, null);

        assertEquals(0.5, render.getStatus().getProgress().getPercent(), 1e-9);
        assertEquals("overworld", render.getStatus().getProgress().getCurrentMap());
        assertEquals(42L, render.getStatus().getProgress().getEtaSeconds());
    }

    @Test
    void detectsAStorageQuotaExceededPodAndStopsRetrying() {
        client.resources(BlueMapMap.class).inNamespace("bluemap-friends").resource(boundMap()).create();
        BlueMapRenderReconciler reconciler = new BlueMapRenderReconciler(client, OperatorConfig.defaults());
        BlueMapRender render = renderWithUid("render-1");
        reconciler.reconcile(render, null);
        createPodForJob("render-1", terminatedContainer("Error", "PutObject failed: QuotaExceeded"));

        reconciler.reconcile(render, null);

        assertEquals("Failed", render.getStatus().getPhase());
        assertEquals("StorageQuotaExceeded", readyReason(render));
    }

    private ContainerStateTerminated terminatedContainer(
            String reason, String message) {
        if (reason == null && message == null) {
            return null;
        }
        return new ContainerStateTerminatedBuilder()
                .withReason(reason)
                .withMessage(message)
                .withExitCode(1)
                .build();
    }

    private void createPodForJob(String jobName, ContainerStateTerminated terminated) {
        var podBuilder = new PodBuilder()
                .withNewMetadata()
                .withName(jobName + "-pod")
                .withNamespace("bluemap-friends")
                .withLabels(Map.of("job-name", jobName))
                .endMetadata()
                .withNewStatus()
                .withPodIP("10.0.0.5")
                .endStatus();
        Pod pod = podBuilder.build();
        if (terminated != null) {
            pod.getStatus()
                    .setContainerStatuses(List.of(new ContainerStatusBuilder()
                            .withName("bluemap")
                            .withState(new ContainerStateBuilder()
                                    .withTerminated(terminated)
                                    .build())
                            .build()));
        }
        client.pods().inNamespace("bluemap-friends").resource(pod).create();
    }
}
