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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import org.junit.jupiter.api.Test;

class RenderJobBuilderTest {

    private BlueMapMap map() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName("survival-overworld")
                .withNamespace("bluemap-friends")
                .build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        map.getSpec().getBluemap().setMinecraftVersion("1.21.10");
        map.getSpec().getStorage().setPrefix("survival");
        map.getStatus().getBucket().setName("apus-friends-survival");
        map.getStatus().getBucket().setEndpoint("http://rgw.example.svc:80");
        return map;
    }

    private BlueMapRender render() {
        BlueMapRender render = new BlueMapRender();
        render.setMetadata(new ObjectMetaBuilder()
                .withName("render-abc")
                .withNamespace("bluemap-friends")
                .build());
        render.getSpec().getMapRef().setName("survival-overworld");
        render.getSpec().setBundleUrl("s3://bundles/worlds/friends/survival/v1/overworld");
        return render;
    }

    private Map<String, EnvVar> envOf(Job job) {
        List<EnvVar> env =
                job.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        return env.stream().collect(Collectors.toMap(EnvVar::getName, Function.identity()));
    }

    @Test
    void suppliesEveryMandatoryEnvironmentVariable() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config", OperatorConfig.defaults());

        Map<String, EnvVar> env = envOf(job);

        // The runner image exits non-zero if any of these is missing.
        for (String required : List.of(
                "APUS_MAP_ID",
                "APUS_DIMENSION",
                "APUS_MC_VERSION",
                "APUS_WORLD_S3_URL",
                "APUS_MAP_BUCKET",
                "APUS_S3_ENDPOINT",
                "APUS_S3_ACCESS_KEY",
                "APUS_S3_SECRET_KEY")) {
            assertNotNull(env.get(required), "missing mandatory variable " + required);
        }
        assertEquals("survival-overworld", env.get("APUS_MAP_ID").getValue());
        assertEquals("1.21.10", env.get("APUS_MC_VERSION").getValue());
    }

    @Test
    void takesCredentialsFromTheSecretRatherThanInliningThem() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config", OperatorConfig.defaults());

        Map<String, EnvVar> env = envOf(job);

        assertNotNull(
                env.get("APUS_S3_ACCESS_KEY").getValueFrom(), "credentials must come from a secretKeyRef");
        assertEquals(
                "bucket-secret",
                env.get("APUS_S3_ACCESS_KEY").getValueFrom().getSecretKeyRef().getName());
        assertNull(
                env.get("APUS_S3_SECRET_KEY").getValue(),
                "the secret must never appear as a literal value in the job manifest");
    }

    @Test
    void doesNotRestartTheJobEndlessly() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config", OperatorConfig.defaults());

        assertNotNull(job.getSpec().getBackoffLimit(), "a render must not retry forever");
        assertTrue(job.getSpec().getBackoffLimit() <= 6, "backoff limit unexpectedly high");
        assertEquals(
                "Never", job.getSpec().getTemplate().getSpec().getRestartPolicy());
    }

    @Test
    void isOwnedByTheRenderResourceSoItIsGarbageCollected() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config", OperatorConfig.defaults());

        assertTrue(
                job.getMetadata().getOwnerReferences().stream()
                        .anyMatch(ref -> "BlueMapRender".equals(ref.getKind())),
                "job must be owned by its BlueMapRender");
    }

    @Test
    void suppliesTheOptionalPrefixVariableWhenTheMapStorageHasOne() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config", OperatorConfig.defaults());

        Map<String, EnvVar> env = envOf(job);

        assertEquals("survival", env.get("APUS_MAP_PREFIX").getValue());
    }

    @Test
    void omitsTheOptionalPrefixVariableWhenTheMapStorageHasNone() {
        BlueMapMap map = map();
        map.getSpec().getStorage().setPrefix(null);

        Job job = RenderJobBuilder.build(render(), map, "bucket-secret", "map-config", OperatorConfig.defaults());

        assertNull(envOf(job).get("APUS_MAP_PREFIX"), "runner already defaults an absent prefix to '.'");
    }

    @Test
    void placesTheContainerImageFromTheOperatorConfig() {
        OperatorConfig config = new OperatorConfig("rook-ceph-fr01", "feather-s3", "ceph-bucket-fr01", "apus/runner:1.2.3");

        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config", config);

        Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertEquals("apus/runner:1.2.3", container.getImage());
    }

    @Test
    void mountsTheGeneratedConfigMapReadOnly() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config", OperatorConfig.defaults());

        List<Volume> volumes = job.getSpec().getTemplate().getSpec().getVolumes();
        assertTrue(
                volumes.stream()
                        .anyMatch(volume -> volume.getConfigMap() != null
                                && "map-config".equals(volume.getConfigMap().getName())),
                "job must mount the ConfigMap the reconciler generated for this map");

        Container container = job.getSpec().getTemplate().getSpec().getContainers().get(0);
        assertTrue(
                container.getVolumeMounts().stream().anyMatch(mount -> Boolean.TRUE.equals(mount.getReadOnly())),
                "config must be mounted read-only, the runner never writes back to it");
    }

    @Test
    void isNamespacedLikeTheRenderItBelongsTo() {
        Job job = RenderJobBuilder.build(render(), map(), "bucket-secret", "map-config", OperatorConfig.defaults());

        assertEquals("bluemap-friends", job.getMetadata().getNamespace());
    }
}
