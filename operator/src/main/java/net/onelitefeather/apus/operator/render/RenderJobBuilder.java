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

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.BlueMapRender;

/**
 * Turns a {@link BlueMapRender} plus the {@link BlueMapMap} it targets into the Kubernetes
 * {@link Job} that actually performs the render, by driving the {@code apus/runner} image
 * (Phase 1) through its environment-variable contract (design spec §7.4).
 *
 * <p>Pure function: no Kubernetes client, no side effects. The caller (a reconciler) is
 * responsible for actually submitting the returned {@link Job} and for having already
 * provisioned the bucket secret and config {@code ConfigMap} this builder only references
 * by name.
 */
public final class RenderJobBuilder {

    /** API group + version the owning {@link BlueMapRender} is served under. */
    private static final String OWNER_API_VERSION = "bluemap.onelitefeather.net/v1alpha1";

    private static final String OWNER_KIND = "BlueMapRender";

    /**
     * A render that keeps failing (bad config, unreachable S3 endpoint) must not retry
     * forever and tie up cluster resources - so this stays small and finite.
     */
    private static final int BACKOFF_LIMIT = 2;

    private static final String CONTAINER_NAME = "bluemap";
    private static final String CONFIG_VOLUME_NAME = "bluemap-config";
    private static final String CONFIG_MOUNT_PATH = "/config";

    private RenderJobBuilder() {}

    /**
     * Builds the render {@link Job} for one {@link BlueMapRender} run.
     *
     * @param render the render run to execute; supplies {@code APUS_WORLD_S3_URL} and
     *     {@code APUS_FORCE_RENDER}, and owns the returned job via an owner reference
     * @param map the {@link BlueMapMap} being rendered; supplies the map id, dimension,
     *     Minecraft version and the destination bucket (from {@code status.bucket}, filled in
     *     by the reconciler that provisions it)
     * @param bucketSecretName name of the Kubernetes {@code Secret}, in the same namespace as
     *     {@code render}, that Rook populated with the bucket's S3 credentials; referenced via
     *     {@code secretKeyRef}, never inlined
     * @param configMapName name of the {@code ConfigMap} carrying this map's generated BlueMap
     *     configuration; mounted read-only into the container
     * @param config operator-wide settings, currently only the runner image to schedule
     * @return the {@link Job} manifest, not yet submitted to the API server
     */
    public static Job build(
            BlueMapRender render, BlueMapMap map, String bucketSecretName, String configMapName, OperatorConfig config) {
        String namespace = render.getMetadata().getNamespace();
        Map<String, String> labels = labels(render, map);

        List<Volume> volumes = new ArrayList<>();
        List<VolumeMount> volumeMounts = new ArrayList<>();
        if (configMapName != null && !configMapName.isBlank()) {
            volumes.add(configVolume(configMapName));
            volumeMounts.add(configVolumeMount());
        }

        Container container = new ContainerBuilder()
                .withName(CONTAINER_NAME)
                .withImage(config.runnerImage())
                .withEnv(env(render, map, bucketSecretName))
                .withResources(resources(map))
                .withVolumeMounts(volumeMounts)
                .build();

        return new JobBuilder()
                .withNewMetadata()
                .withName(render.getMetadata().getName())
                .withNamespace(namespace)
                .withLabels(labels)
                .withOwnerReferences(ownerReference(render))
                .endMetadata()
                .withNewSpec()
                .withBackoffLimit(BACKOFF_LIMIT)
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withContainers(container)
                .withVolumes(volumes)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    private static Map<String, String> labels(BlueMapRender render, BlueMapMap map) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app.kubernetes.io/managed-by", "apus-operator");
        labels.put("app.kubernetes.io/name", "bluemap-render");
        labels.put("app.kubernetes.io/instance", render.getMetadata().getName());
        labels.put("bluemap.onelitefeather.net/map", map.getMetadata().getName());
        return labels;
    }

    private static OwnerReference ownerReference(BlueMapRender render) {
        return new OwnerReferenceBuilder()
                .withApiVersion(OWNER_API_VERSION)
                .withKind(OWNER_KIND)
                .withName(render.getMetadata().getName())
                .withUid(render.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    /**
     * Builds the environment for the {@code bluemap} container to satisfy the Phase 1 runner's
     * contract exactly (design spec §7.4). Every mandatory variable is always set; optional
     * ones are only added when the data model actually carries a value for them, so the
     * runner's own defaults apply otherwise.
     */
    private static List<EnvVar> env(BlueMapRender render, BlueMapMap map, String bucketSecretName) {
        List<EnvVar> env = new ArrayList<>();

        // Mandatory - the runner exits non-zero at startup if any of these is missing.
        env.add(literal("APUS_MAP_ID", map.getMetadata().getName()));
        env.add(literal("APUS_DIMENSION", map.getSpec().getSource().getDimension()));
        env.add(literal(
                "APUS_MC_VERSION", map.getSpec().getBluemap().getMinecraftVersion()));
        env.add(literal("APUS_WORLD_S3_URL", render.getSpec().getBundleUrl()));
        env.add(literal("APUS_MAP_BUCKET", map.getStatus().getBucket().getName()));
        env.add(literal("APUS_S3_ENDPOINT", map.getStatus().getBucket().getEndpoint()));
        env.add(fromSecret("APUS_S3_ACCESS_KEY", bucketSecretName, "AWS_ACCESS_KEY_ID"));
        env.add(fromSecret("APUS_S3_SECRET_KEY", bucketSecretName, "AWS_SECRET_ACCESS_KEY"));

        // Optional - only set when the CR actually carries a non-default value.
        String prefix = map.getSpec().getStorage().getPrefix();
        if (prefix != null && !prefix.isBlank()) {
            env.add(literal("APUS_MAP_PREFIX", prefix));
        }
        env.add(literal("APUS_FORCE_RENDER", Boolean.toString(render.getSpec().isForce())));

        return env;
    }

    private static EnvVar literal(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    /** Credentials must come from the Secret Rook populated, never as a literal value. */
    private static EnvVar fromSecret(String name, String secretName, String key) {
        return new EnvVarBuilder()
                .withName(name)
                .withNewValueFrom()
                .withNewSecretKeyRef()
                .withName(secretName)
                .withKey(key)
                .endSecretKeyRef()
                .endValueFrom()
                .build();
    }

    /**
     * Applies {@code BlueMapMap.spec.resources} to the render pod, if set. Both requests and
     * limits are pinned to the same value: a render job that exceeds its own sizing should
     * fail fast rather than silently burst into shared node capacity.
     */
    private static ResourceRequirements resources(BlueMapMap map) {
        String cpu = map.getSpec().getResources().getCpu();
        String memory = map.getSpec().getResources().getMemory();
        if ((cpu == null || cpu.isBlank()) && (memory == null || memory.isBlank())) {
            return null;
        }

        Map<String, Quantity> quantities = new LinkedHashMap<>();
        if (cpu != null && !cpu.isBlank()) {
            quantities.put("cpu", new Quantity(cpu));
        }
        if (memory != null && !memory.isBlank()) {
            quantities.put("memory", new Quantity(memory));
        }

        return new ResourceRequirementsBuilder()
                .withRequests(quantities)
                .withLimits(quantities)
                .build();
    }

    private static Volume configVolume(String configMapName) {
        return new VolumeBuilder()
                .withName(CONFIG_VOLUME_NAME)
                .withNewConfigMap()
                .withName(configMapName)
                .endConfigMap()
                .build();
    }

    private static VolumeMount configVolumeMount() {
        return new VolumeMountBuilder()
                .withName(CONFIG_VOLUME_NAME)
                .withMountPath(CONFIG_MOUNT_PATH)
                .withReadOnly(true)
                .build();
    }
}
