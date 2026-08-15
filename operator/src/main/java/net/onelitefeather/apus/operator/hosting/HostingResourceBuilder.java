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
package net.onelitefeather.apus.operator.hosting;

import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.KeyToPath;
import io.fabric8.kubernetes.api.model.KeyToPathBuilder;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLS;
import io.fabric8.kubernetes.api.model.networking.v1.IngressTLSBuilder;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapHosting;
import net.onelitefeather.apus.operator.api.Labels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a {@link BlueMapHosting} into the Kubernetes objects that make an already-rendered map
 * reachable on the web: a {@link Deployment} running the BlueMap webserver, a {@link Service}
 * fronting its pods, an {@link Ingress} exposing that Service under the requested hostname, and
 * -- unless TLS is disabled -- a cert-manager {@link Certificate} backing the ingress's TLS
 * secret.
 *
 * <p>Pure function: no Kubernetes client, no side effects, following the same shape as {@link
 * net.onelitefeather.apus.operator.render.RenderJobBuilder}. The caller (the eventual {@code
 * BlueMapHostingReconciler}, phase 3 task 4) is responsible for actually submitting these
 * objects, for building {@code configMapName}'s content via {@code
 * net.onelitefeather.apus.operator.map.BlueMapConfigBuilder#buildForHosting}, and for having
 * already resolved {@code bucketSecretName} to a Secret Rook populated with S3 credentials.
 */
public final class HostingResourceBuilder {

    private static final Logger LOGGER = LoggerFactory.getLogger(HostingResourceBuilder.class);

    /** API group + version the owning {@link BlueMapHosting} is served under. */
    private static final String OWNER_API_VERSION = "bluemap.onelitefeather.net/v1alpha1";

    private static final String OWNER_KIND = "BlueMapHosting";

    private static final String CONTAINER_NAME = "bluemap";

    private static final String CONFIG_VOLUME_NAME = "hosting-config";

    /**
     * Mount path for the read-only {@code hosting-config} ConfigMap volume. The Task 2 image's
     * entrypoint reads the map/storage configuration this operator generated from here, copies
     * it into a writable directory (a ConfigMap mount is read-only) and fills in the S3
     * credentials before starting BlueMap -- see the phase 3 plan's Task 2 section.
     */
    static final String CONFIG_MOUNT_PATH = "/config-src";

    /**
     * Separator {@link #configMapKey} substitutes for {@code /} in a {@code
     * BlueMapConfigBuilder#buildForHosting} logical file path (e.g. {@code
     * maps/survival-overworld.conf}) to turn it into a valid {@code ConfigMap} data key.
     *
     * <p>Kubernetes rejects a {@code ConfigMap} data key containing {@code /} outright (the API
     * server enforces {@code [-._a-zA-Z0-9]+}) -- the fabric8 mock server used by every test in
     * this module below the real-cluster integration test does not enforce that, so this was only
     * ever caught once {@code BlueMapHostingReconciler} tried to actually create a {@code
     * ConfigMap} against a real k3s API server. {@code .} is itself a valid key character and
     * never appears in a logical path except as the file extension, so the substitution is
     * unambiguous without needing a matching "unflatten" step: {@link
     * #deployment(BlueMapHosting, String, Collection, String, OperatorConfig)} instead carries the
     * original, un-substituted logical path forward as the {@link KeyToPath#getPath()} of a
     * ConfigMap volume {@code item}, which -- unlike a data key -- Kubernetes does allow to
     * contain {@code /}, and is exactly how the file ends up back at its original nested location
     * ({@code maps/survival-overworld.conf}) inside the container, matching what {@code
     * hosting/bin/config-sync.sh} has always expected to find under {@link #CONFIG_MOUNT_PATH}.
     */
    private static final char CONFIG_MAP_KEY_SEPARATOR = '.';

    /**
     * Port the BlueMap webserver listens on inside the container, and the port the {@link
     * Service} and readiness/liveness probes target. Matches {@code
     * BlueMapConfigBuilder#buildForHosting}'s {@code webserverPort} parameter and the Task 2
     * image's {@code APUS_WEBSERVER_PORT} default (both currently fixed at 8100, since neither
     * {@link BlueMapHosting} nor {@link OperatorConfig} exposes a port field) -- keep these three
     * in sync if that ever changes.
     */
    static final int WEBSERVER_PORT = 8100;

    /**
     * HTTP path used for both the readiness and liveness probe.
     *
     * <p>Verified against Task 2's actual image (see {@code
     * hosting/entrypoint.sh}/{@code hosting/README.md} and the phase 3 SDD ledger): {@code
     * GET /settings.json} only returns 200 once the BlueMap webserver has actually generated its
     * web-app shell and is serving it -- unlike {@code /}, which 404s during that same window.
     * A pod must not receive traffic (readiness) or be considered alive (liveness) before that.
     */
    static final String PROBE_PATH = "/settings.json";

    private HostingResourceBuilder() {}

    /**
     * Builds the {@link Deployment} running the BlueMap webserver for one {@link BlueMapHosting}.
     *
     * @param hosting the hosting resource this deployment serves; supplies replica count,
     *     resource sizing, and owns the returned deployment via an owner reference
     * @param configMapName name of the {@code ConfigMap}, in the same namespace as {@code
     *     hosting}, holding the map/storage/webserver configuration built by {@code
     *     BlueMapConfigBuilder#buildForHosting}; mounted read-only
     * @param configFileNames the logical file paths that map/storage/webserver configuration was
     *     built under (i.e. {@code BlueMapConfigBuilder.buildForHosting(...)}'s returned map's
     *     {@code keySet()}, e.g. {@code maps/survival-overworld.conf}) -- used to map each
     *     sanitised {@link #configMapKey} back to its original nested path inside the container,
     *     via the {@code ConfigMap} volume's {@code items}; must be the exact same set the
     *     {@code ConfigMap} passed as {@code configMapName} was built from, or the volume mount
     *     will be missing files (or 404 on ones that were renamed away)
     * @param bucketSecretName name of the Kubernetes {@code Secret}, in the same namespace as
     *     {@code hosting}, carrying the S3 credentials the webserver needs to read the already-
     *     rendered maps; referenced via {@code secretKeyRef}, never inlined
     * @param config operator-wide settings; supplies the hosting webserver's container image via
     *     {@link OperatorConfig#hostingImage()}
     * @return the {@link Deployment} manifest, not yet submitted to the API server
     */
    public static Deployment deployment(
            BlueMapHosting hosting,
            String configMapName,
            Collection<String> configFileNames,
            String bucketSecretName,
            OperatorConfig config) {
        String namespace = hosting.getMetadata().getNamespace();
        Map<String, String> labels = labels(hosting);
        LOGGER.debug(
                "building hosting deployment '{}' in namespace '{}' from image '{}' with {} config file(s)",
                hosting.getMetadata().getName(),
                namespace,
                config.hostingImage(),
                configFileNames.size());

        Container container = new ContainerBuilder()
                .withName(CONTAINER_NAME)
                .withImage(config.hostingImage())
                .withPorts(containerPort())
                .withEnv(env(bucketSecretName))
                .withResources(resources(hosting))
                .withVolumeMounts(configVolumeMount())
                .withReadinessProbe(probe())
                .withLivenessProbe(probe())
                .build();

        Volume configVolume = new VolumeBuilder()
                .withName(CONFIG_VOLUME_NAME)
                .withConfigMap(new ConfigMapVolumeSourceBuilder()
                        .withName(configMapName)
                        .withItems(configVolumeItems(configFileNames))
                        .build())
                .build();

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(hosting.getMetadata().getName())
                .withNamespace(namespace)
                .withLabels(labels)
                .withOwnerReferences(ownerReference(hosting))
                .endMetadata()
                .withNewSpec()
                .withReplicas(hosting.getSpec().getReplicas())
                .withNewSelector()
                .withMatchLabels(labels)
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .withVolumes(configVolume)
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * Builds the {@link Service} fronting the webserver pods of one {@link BlueMapHosting}.
     *
     * @param hosting the hosting resource this service belongs to
     * @return the {@link Service} manifest, not yet submitted to the API server
     */
    public static Service service(BlueMapHosting hosting) {
        Map<String, String> labels = labels(hosting);

        ServicePort port = new ServicePortBuilder()
                .withName("http")
                .withPort(WEBSERVER_PORT)
                .withNewTargetPort(WEBSERVER_PORT)
                .build();

        return new ServiceBuilder()
                .withNewMetadata()
                .withName(hosting.getMetadata().getName())
                .withNamespace(hosting.getMetadata().getNamespace())
                .withLabels(labels)
                .withOwnerReferences(ownerReference(hosting))
                .endMetadata()
                .withNewSpec()
                .withSelector(labels)
                .withPorts(port)
                .endSpec()
                .build();
    }

    /**
     * Builds the {@link Ingress} exposing one {@link BlueMapHosting}'s Service under its
     * configured hostname. Carries a {@code tls} section, referencing the {@link Certificate}
     * {@link #certificate(BlueMapHosting)} would build, exactly when TLS is enabled.
     *
     * @param hosting the hosting resource this ingress belongs to
     * @return the {@link Ingress} manifest, not yet submitted to the API server
     */
    public static Ingress ingress(BlueMapHosting hosting) {
        String serviceName = hosting.getMetadata().getName();
        String hostname = hosting.getSpec().getHostname();

        var backend = new IngressBackendBuilder()
                .withService(new IngressServiceBackendBuilder()
                        .withName(serviceName)
                        .withNewPort()
                        .withNumber(WEBSERVER_PORT)
                        .endPort()
                        .build())
                .build();

        var path = new HTTPIngressPathBuilder()
                .withPath("/")
                .withPathType("Prefix")
                .withBackend(backend)
                .build();

        var rule = new IngressRuleBuilder()
                .withHost(hostname)
                .withNewHttp()
                .withPaths(path)
                .endHttp()
                .build();

        var ingressBuilder = new IngressBuilder()
                .withNewMetadata()
                .withName(hosting.getMetadata().getName())
                .withNamespace(hosting.getMetadata().getNamespace())
                .withLabels(labels(hosting))
                .withOwnerReferences(ownerReference(hosting))
                .endMetadata()
                .withNewSpec()
                .withIngressClassName(hosting.getSpec().getIngressClassName())
                .withRules(rule);

        if (hosting.getSpec().getTls().isEnabled()) {
            ingressBuilder.withTls(List.of(tls(hosting)));
        }

        return ingressBuilder.endSpec().build();
    }

    /**
     * Builds the cert-manager {@link Certificate} backing this hosting's ingress TLS secret.
     *
     * @param hosting the hosting resource requesting TLS
     * @return the certificate to submit, or empty when {@code spec.tls.enabled} is {@code false}
     *     -- in which case no {@code Certificate} must be created and the ingress carries no TLS
     *     section either, see {@link #ingress(BlueMapHosting)}
     */
    public static Optional<Certificate> certificate(BlueMapHosting hosting) {
        if (!hosting.getSpec().getTls().isEnabled()) {
            return Optional.empty();
        }

        Certificate certificate = new Certificate();
        certificate.setMetadata(new ObjectMetaBuilder()
                .withName(hosting.getMetadata().getName())
                .withNamespace(hosting.getMetadata().getNamespace())
                .withLabels(labels(hosting))
                .withOwnerReferences(ownerReference(hosting))
                .build());

        certificate.getSpec().setSecretName(tlsSecretName(hosting));
        certificate.getSpec().setDnsNames(List.of(hosting.getSpec().getHostname()));
        certificate.getSpec().getIssuerRef().setName(hosting.getSpec().getTls().getIssuerRef().getName());
        certificate.getSpec().getIssuerRef().setKind(hosting.getSpec().getTls().getIssuerKind());

        return Optional.of(certificate);
    }

    private static IngressTLS tls(BlueMapHosting hosting) {
        return new IngressTLSBuilder()
                .withHosts(hosting.getSpec().getHostname())
                .withSecretName(tlsSecretName(hosting))
                .build();
    }

    /**
     * Name of the {@code Secret} cert-manager writes the certificate into, and the name the
     * ingress's {@code tls[].secretName} must reference. Computed identically by {@link
     * #ingress(BlueMapHosting)} and {@link #certificate(BlueMapHosting)} so the two always agree
     * without either method having to call the other.
     */
    private static String tlsSecretName(BlueMapHosting hosting) {
        return hosting.getMetadata().getName() + "-tls";
    }

    private static Map<String, String> labels(BlueMapHosting hosting) {
        return Labels.standard("bluemap-hosting", hosting.getMetadata().getName());
    }

    private static OwnerReference ownerReference(BlueMapHosting hosting) {
        return new OwnerReferenceBuilder()
                .withApiVersion(OWNER_API_VERSION)
                .withKind(OWNER_KIND)
                .withName(hosting.getMetadata().getName())
                .withUid(hosting.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    private static ContainerPort containerPort() {
        return new ContainerPortBuilder()
                .withName("http")
                .withContainerPort(WEBSERVER_PORT)
                .build();
    }

    /**
     * Credentials for the S3 bucket(s) the mounted configuration references, taken from the Rook-
     * managed Secret rather than inlined -- a Deployment manifest is readable by anything allowed
     * to read Deployments in the namespace. The endpoint itself is not passed here: {@code
     * BlueMapConfigBuilder#buildForHosting} already bakes it into each map's {@code
     * storages/<id>.conf} file at ConfigMap-build time, so the entrypoint only ever needs to fill
     * in the two credential lines those files deliberately leave blank.
     */
    private static List<EnvVar> env(String bucketSecretName) {
        return List.of(
                fromSecret("APUS_S3" + "_ACCESS_KEY", bucketSecretName, "AWS_ACCESS_KEY_ID"),
                fromSecret("APUS_S3" + "_SECRET_KEY", bucketSecretName, "AWS_SECRET_ACCESS_KEY"),
                literal("APUS_WEBSERVER_PORT", Integer.toString(WEBSERVER_PORT)));
    }

    private static EnvVar literal(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

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
     * Builds the {@code ConfigMap} volume's {@code items} list: one entry per logical config
     * file, mapping its sanitised {@link #configMapKey} back to the original nested {@code path}
     * (e.g. {@code maps/survival-overworld.conf}) the file must land at inside the container --
     * see {@link #CONFIG_MAP_KEY_SEPARATOR}'s Javadoc for why the data key itself cannot carry
     * that path directly. Sorted for a deterministic manifest.
     */
    private static List<KeyToPath> configVolumeItems(Collection<String> configFileNames) {
        return configFileNames.stream()
                .sorted()
                .map(path -> new KeyToPathBuilder()
                        .withKey(configMapKey(path))
                        .withPath(path)
                        .build())
                .toList();
    }

    /**
     * Sanitises a {@code BlueMapConfigBuilder#buildForHosting} logical file path into a valid
     * {@code ConfigMap} data key -- see {@link #CONFIG_MAP_KEY_SEPARATOR}'s Javadoc. Package-
     * private so {@code BlueMapHostingReconciler} can build the {@code ConfigMap}'s {@code data}
     * map with the exact same keys {@link #configVolumeItems} expects to find.
     */
    static String configMapKey(String logicalPath) {
        return logicalPath.replace('/', CONFIG_MAP_KEY_SEPARATOR);
    }

    private static VolumeMount configVolumeMount() {
        return new VolumeMountBuilder()
                .withName(CONFIG_VOLUME_NAME)
                .withMountPath(CONFIG_MOUNT_PATH)
                .withReadOnly(true)
                .build();
    }

    /**
     * A pod whose webserver has not finished loading its maps from S3 yet must not receive
     * traffic (readiness) and must be restarted if it stops responding entirely (liveness) --
     * see the phase 3 plan's "Betriebsrelevant" note on this task. Both probes share the same
     * HTTP check since BlueMap's webserver has no separate startup/liveness endpoint.
     */
    private static Probe probe() {
        return new ProbeBuilder()
                .withNewHttpGet()
                .withPath(PROBE_PATH)
                .withNewPort(WEBSERVER_PORT)
                .endHttpGet()
                .build();
    }

    /**
     * Applies {@code BlueMapHosting.spec.resources} to the webserver pod, if set. Mirrors {@code
     * RenderJobBuilder#resources(BlueMapMap)}, including pinning requests and limits to the same
     * value.
     */
    private static ResourceRequirements resources(BlueMapHosting hosting) {
        String cpu = hosting.getSpec().getResources().getCpu();
        String memory = hosting.getSpec().getResources().getMemory();
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
}
