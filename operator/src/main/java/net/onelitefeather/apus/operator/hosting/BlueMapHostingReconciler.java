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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapHosting;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.Ref;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.map.BlueMapConfigBuilder;

/**
 * Turns a {@link BlueMapHosting} into a running, publicly reachable BlueMap webserver: a {@code
 * ConfigMap} carrying its multi-map configuration ({@link BlueMapConfigBuilder#buildForHosting}),
 * a {@link Deployment}, {@link Service}, {@link Ingress}, and -- when TLS is enabled and
 * cert-manager is installed -- a {@link Certificate}, all built by {@link HostingResourceBuilder}.
 *
 * <p><b>S1 -- a hostname must be permitted by its tenant (design spec §8.1).</b> {@link
 * HostingResourceBuilder} is a pure function that writes {@code spec.hostname} into the ingress
 * unchecked; nothing before this reconciler existed enforced {@code
 * Tenant.spec.hosting.allowedDomains}, which would let a tenant claim another tenant's hostname
 * and pull its traffic. This class closes that gap: it resolves the owning {@link Tenant} from
 * the {@link Labels#TENANT} label {@link net.onelitefeather.apus.operator.tenant.TenantReconciler}
 * stamps on every tenant namespace (a {@link BlueMapHosting} carries no direct tenant reference,
 * exactly like {@link BlueMapMap} -- see {@code BlueMapMapReconciler}'s identical derivation), and
 * matches {@code spec.hostname} against that tenant's {@code allowedDomains} -- literal hostnames
 * or single-level wildcards ({@code *.friends.example.net}, matching exactly one extra label the
 * way a wildcard TLS certificate would, not an arbitrary number of subdomain levels). A mismatch
 * creates <b>no</b> Ingress and <b>no</b> Deployment -- only a {@code HostnameNotAllowed}
 * condition.
 *
 * <p><b>An empty {@code allowedDomains} list means "no hosting permitted", not "unrestricted".</b>
 * See {@code TenantSpec.Hosting}'s Javadoc for the reasoning: an unset list is far more likely to
 * mean "this tenant was never configured for hosting" than "a platform administrator deliberately
 * allowed any hostname".
 *
 * <p><b>S2 -- referenced maps must live in this hosting's own namespace.</b> {@link Ref}
 * deliberately carries no namespace field (see its Javadoc, design spec §10.1), so a {@link
 * BlueMapMap} reference can only ever be resolved inside {@code hosting}'s own namespace -- this
 * reconciler does exactly that via {@code client.resources(BlueMapMap.class).inNamespace(...)}
 * rather than a cluster-wide lookup. A map that does not exist there is reported as a {@code
 * MapNotFound} condition, never silently searched for elsewhere.
 *
 * <p><b>A referenced map needs a bound bucket before this hosting is built.</b> Mirrors {@code
 * BlueMapRenderReconciler}'s identical precondition on {@code BlueMapMap.status.bucket}: a
 * webserver pointed at an empty bucket name would just serve a broken page, so no Deployment is
 * created (reason {@value #MAP_NOT_READY_REASON}) until every referenced map's bucket is bound.
 *
 * <p><b>Ownership check, mirroring {@code BlueMapRenderReconciler}'s.</b> Every resource {@link
 * HostingResourceBuilder} builds (and the {@code ConfigMap} this class builds itself) carries an
 * owner reference naming this {@link BlueMapHosting} by both name and UID. Before writing any of
 * them, an existing resource of the same name is checked against that owner reference; a mismatch
 * (a resource that exists but was not created by this hosting) aborts with a {@code
 * ResourceConflict} condition instead of adopting it. All checks run before any write, so a
 * conflict on a later resource never leaves an earlier one silently created.
 *
 * <p><b>cert-manager may not be installed.</b> Mirrors {@code TenantReconciler}/{@code
 * BlueMapMapReconciler}'s handling of Rook: {@link io.fabric8.kubernetes.client.Client#supports}
 * is checked for {@link Certificate} before this class -- or {@link HostingResourceBuilder} on its
 * behalf -- ever touches one. If TLS is requested but cert-manager's CRD is not registered, no
 * resource at all is created (reason {@value #CERT_MANAGER_UNAVAILABLE_REASON}) rather than
 * standing up an Ingress whose {@code tls[].secretName} would never be populated.
 *
 * <p><b>A config change must restart the pods.</b> BlueMap only reads its configuration at
 * startup, so a webserver that already has pods running would otherwise keep serving a stale map
 * list forever after {@code spec.maps} changes. This reconciler hashes the generated config files
 * (SHA-256 over their sorted file names and content) and stamps that hash onto the Deployment's
 * pod template as an annotation; a changed hash changes the pod template, which is exactly what
 * makes the Deployment controller roll the pods.
 *
 * <p><b>All maps in a hosting are assumed to share one set of S3 credentials.</b> {@link
 * HostingResourceBuilder#deployment} accepts exactly one {@code bucketSecretName}, and the
 * hosting image's entrypoint (Task 2) applies that one credential pair to every {@code
 * storages/*.conf} file it copies in. This reconciler passes the first referenced map's {@code
 * status.bucket.secretName}. That is not a limitation in practice: {@code BucketProvisioner}
 * always creates a map's {@code ObjectBucketClaim} with {@code additionalConfig.bucketOwner} set
 * to the tenant's single Ceph object-store user ({@code TenantReconciler#cephUserFor}), so every
 * bucket a tenant's maps live in is owned by that same Ceph user regardless of which map's claim
 * the credentials Secret happens to be named after.
 *
 * <p><b>Idempotent:</b> every write goes through {@code createOr(NonDeletingOperation::update)},
 * exactly like every other reconciler in this module (the fabric8 mock server used in tests does
 * not support server-side apply); reconciling an already-up-to-date hosting changes nothing.
 */
@ControllerConfiguration
public class BlueMapHostingReconciler implements Reconciler<BlueMapHosting> {

    /** Reason set when the namespace's owning tenant cannot be resolved. */
    public static final String TENANT_NOT_FOUND_REASON = "TenantNotFound";

    /** Reason set when the tenant has no {@code allowedDomains} configured at all. */
    public static final String HOSTING_NOT_CONFIGURED_REASON = "HostingNotConfigured";

    /** Reason set when {@code spec.hostname} does not match any of the tenant's allowed domains. */
    public static final String HOSTNAME_NOT_ALLOWED_REASON = "HostnameNotAllowed";

    /** Reason set when a referenced map does not exist in this hosting's own namespace. */
    public static final String MAP_NOT_FOUND_REASON = "MapNotFound";

    /** Reason set while a referenced map exists but has no bound bucket yet. */
    public static final String MAP_NOT_READY_REASON = "MapNotReady";

    /** Reason set when an existing resource fails the ownership check. */
    public static final String RESOURCE_CONFLICT_REASON = "ResourceConflict";

    /** Reason set when TLS is requested but cert-manager's {@code Certificate} CRD is missing. */
    public static final String CERT_MANAGER_UNAVAILABLE_REASON = "CertManagerUnavailable";

    /** Reason set while the Deployment has not yet reached its desired ready replica count. */
    public static final String DEPLOYMENT_NOT_READY_REASON = "DeploymentNotReady";

    /** Reason set once the Deployment is ready and {@code status.url} is populated. */
    public static final String HOSTING_READY_REASON = "HostingReady";

    private static final String OWNER_API_VERSION = "bluemap.onelitefeather.net/v1alpha1";
    private static final String OWNER_KIND = "BlueMapHosting";

    /**
     * Rook/RGW does not distinguish real AWS regions, and {@code BlueMapMap.status.bucket} does
     * not carry one -- mirrors the {@code us-east-1} default every other Apus component
     * (runner, ingest) already falls back to.
     */
    private static final String DEFAULT_BUCKET_REGION = "us-east-1";

    /**
     * Annotation carrying the SHA-256 of the generated config files, stamped onto the Deployment
     * pod template so a config change forces a rollout -- see the class Javadoc.
     */
    static final String CONFIG_CHECKSUM_ANNOTATION = "apus.onelitefeather.net/config-checksum";

    private static final Duration RECHECK_INTERVAL = Duration.ofSeconds(10);

    private final KubernetesClient client;
    private final OperatorConfig config;

    public BlueMapHostingReconciler(KubernetesClient client, OperatorConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public UpdateControl<BlueMapHosting> reconcile(BlueMapHosting hosting, Context<BlueMapHosting> context) {
        String namespace = hosting.getMetadata().getNamespace();
        String name = hosting.getMetadata().getName();

        Optional<String> tenantName = resolveTenantName(namespace);
        if (tenantName.isEmpty()) {
            return pending(
                    hosting,
                    TENANT_NOT_FOUND_REASON,
                    "namespace '" + namespace + "' is not labelled with an owning tenant yet");
        }
        Tenant tenant = client.resources(Tenant.class).withName(tenantName.get()).get();
        if (tenant == null) {
            return pending(
                    hosting,
                    TENANT_NOT_FOUND_REASON,
                    "tenant '" + tenantName.get() + "' referenced by namespace '" + namespace + "' does not exist");
        }

        List<String> allowedDomains = tenant.getSpec().getHosting().getAllowedDomains();
        String hostname = hosting.getSpec().getHostname();
        if (allowedDomains == null || allowedDomains.isEmpty()) {
            return pending(
                    hosting,
                    HOSTING_NOT_CONFIGURED_REASON,
                    "tenant '" + tenantName.get()
                            + "' has no allowedDomains configured; hosting is not permitted until at least one is"
                            + " set");
        }
        if (!hostnameAllowed(hostname, allowedDomains)) {
            return pending(
                    hosting,
                    HOSTNAME_NOT_ALLOWED_REASON,
                    "hostname '" + hostname + "' is not covered by tenant '" + tenantName.get()
                            + "'s allowedDomains " + allowedDomains);
        }

        List<BlueMapMap> maps = new ArrayList<>();
        for (Ref ref : hosting.getSpec().getMaps()) {
            String mapName = ref.getName();
            BlueMapMap map =
                    client.resources(BlueMapMap.class).inNamespace(namespace).withName(mapName).get();
            if (map == null) {
                return pending(
                        hosting,
                        MAP_NOT_FOUND_REASON,
                        "map '" + mapName + "' does not exist in namespace '" + namespace + "'");
            }
            if (!isBucketBound(map)) {
                return pending(hosting, MAP_NOT_READY_REASON, "map '" + mapName + "' has no bound bucket yet");
            }
            maps.add(map);
        }

        boolean tlsEnabled = hosting.getSpec().getTls().isEnabled();
        boolean certManagerAvailable = client.supports(Certificate.class);
        if (tlsEnabled && !certManagerAvailable) {
            return pending(
                    hosting,
                    CERT_MANAGER_UNAVAILABLE_REASON,
                    "TLS is enabled but the cert-manager Certificate CRD (cert-manager.io) is not registered on"
                            + " this cluster");
        }

        String configMapName = name + "-config";
        Optional<UpdateControl<BlueMapHosting>> conflict =
                checkOwnership(hosting, namespace, name, configMapName, tlsEnabled, certManagerAvailable);
        if (conflict.isPresent()) {
            return conflict.get();
        }

        List<BlueMapConfigBuilder.BucketBinding> bindings = maps.stream()
                .map(map -> new BlueMapConfigBuilder.BucketBinding(
                        map.getStatus().getBucket().getName(),
                        map.getStatus().getBucket().getEndpoint(),
                        DEFAULT_BUCKET_REGION))
                .toList();
        Map<String, String> files =
                BlueMapConfigBuilder.buildForHosting(maps, bindings, HostingResourceBuilder.WEBSERVER_PORT);
        String checksum = checksum(files);

        client.configMaps()
                .inNamespace(namespace)
                .resource(buildConfigMap(hosting, configMapName, files))
                .createOr(NonDeletingOperation::update);

        String bucketSecretName = maps.get(0).getStatus().getBucket().getSecretName();
        Deployment deployment = HostingResourceBuilder.deployment(hosting, configMapName, bucketSecretName, config);
        stampConfigChecksum(deployment, checksum);
        Deployment existingDeployment =
                client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (!deploymentUpToDate(existingDeployment, deployment)) {
            client.apps()
                    .deployments()
                    .inNamespace(namespace)
                    .resource(deployment)
                    .createOr(NonDeletingOperation::update);
        }

        client.services()
                .inNamespace(namespace)
                .resource(HostingResourceBuilder.service(hosting))
                .createOr(NonDeletingOperation::update);

        client.network()
                .v1()
                .ingresses()
                .inNamespace(namespace)
                .resource(HostingResourceBuilder.ingress(hosting))
                .createOr(NonDeletingOperation::update);

        if (tlsEnabled) {
            HostingResourceBuilder.certificate(hosting)
                    .ifPresent(certificate -> client.resources(Certificate.class)
                            .inNamespace(namespace)
                            .resource(certificate)
                            .createOr(NonDeletingOperation::update));
        }

        return updateReadiness(hosting, namespace, name);
    }

    /**
     * Recovers the tenant name owning {@code namespace} from the {@link Labels#TENANT} label
     * {@code TenantReconciler} stamps on every tenant namespace it creates. A {@link
     * BlueMapHosting} carries no direct reference to its tenant -- only the namespace it lives
     * in -- so this is the only way back, exactly like {@code
     * BlueMapMapReconciler#cephUserForNamespace} recovers the tenant name for a different
     * purpose from the same namespace.
     */
    private Optional<String> resolveTenantName(String namespace) {
        Namespace ns = client.namespaces().withName(namespace).get();
        if (ns == null || ns.getMetadata().getLabels() == null) {
            return Optional.empty();
        }
        String tenantName = ns.getMetadata().getLabels().get(Labels.TENANT);
        return (tenantName == null || tenantName.isBlank()) ? Optional.empty() : Optional.of(tenantName);
    }

    /**
     * Matches {@code hostname} against a tenant's {@code allowedDomains} (design spec §8.1) --
     * literal, case-insensitive equality, or a single-level wildcard ({@code
     * *.friends.example.net} matches {@code maps.friends.example.net} but not {@code
     * a.b.friends.example.net}), mirroring how a wildcard TLS certificate itself only ever covers
     * one label. Never called with an empty {@code allowedDomains} -- {@link #reconcile} already
     * refuses hosting entirely in that case (see the class Javadoc).
     */
    private static boolean hostnameAllowed(String hostname, List<String> allowedDomains) {
        if (hostname == null || hostname.isBlank()) {
            return false;
        }
        String normalizedHost = hostname.toLowerCase(Locale.ROOT);
        for (String domain : allowedDomains) {
            if (domain == null || domain.isBlank()) {
                continue;
            }
            String normalizedDomain = domain.toLowerCase(Locale.ROOT);
            if (normalizedDomain.startsWith("*.")) {
                if (matchesSingleLevelWildcard(normalizedHost, normalizedDomain.substring(2))) {
                    return true;
                }
            } else if (normalizedHost.equals(normalizedDomain)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesSingleLevelWildcard(String host, String suffix) {
        if (suffix.isEmpty() || !host.endsWith("." + suffix)) {
            return false;
        }
        String label = host.substring(0, host.length() - suffix.length() - 1);
        return !label.isEmpty() && !label.contains(".");
    }

    private static boolean isBucketBound(BlueMapMap map) {
        var bucket = map.getStatus().getBucket();
        return bucket.getName() != null
                && !bucket.getName().isBlank()
                && bucket.getSecretName() != null
                && !bucket.getSecretName().isBlank();
    }

    /**
     * Checks every resource this reconciler is about to write against its owner reference,
     * before any of them are actually written -- see the class Javadoc's "Ownership check"
     * section. Returns the conflict {@link UpdateControl} to return from {@link #reconcile} if
     * one is found, or empty if every existing resource (or lack thereof) is safe to write to.
     */
    private Optional<UpdateControl<BlueMapHosting>> checkOwnership(
            BlueMapHosting hosting,
            String namespace,
            String name,
            String configMapName,
            boolean tlsEnabled,
            boolean certManagerAvailable) {
        ConfigMap existingConfigMap =
                client.configMaps().inNamespace(namespace).withName(configMapName).get();
        if (existingConfigMap != null
                && !ownedByHosting(existingConfigMap.getMetadata().getOwnerReferences(), hosting)) {
            return Optional.of(conflict(hosting, "ConfigMap", configMapName));
        }

        Deployment existingDeployment =
                client.apps().deployments().inNamespace(namespace).withName(name).get();
        if (existingDeployment != null
                && !ownedByHosting(existingDeployment.getMetadata().getOwnerReferences(), hosting)) {
            return Optional.of(conflict(hosting, "Deployment", name));
        }

        Service existingService = client.services().inNamespace(namespace).withName(name).get();
        if (existingService != null
                && !ownedByHosting(existingService.getMetadata().getOwnerReferences(), hosting)) {
            return Optional.of(conflict(hosting, "Service", name));
        }

        Ingress existingIngress =
                client.network().v1().ingresses().inNamespace(namespace).withName(name).get();
        if (existingIngress != null
                && !ownedByHosting(existingIngress.getMetadata().getOwnerReferences(), hosting)) {
            return Optional.of(conflict(hosting, "Ingress", name));
        }

        if (tlsEnabled && certManagerAvailable) {
            Certificate existingCertificate =
                    client.resources(Certificate.class).inNamespace(namespace).withName(name).get();
            if (existingCertificate != null
                    && !ownedByHosting(existingCertificate.getMetadata().getOwnerReferences(), hosting)) {
                return Optional.of(conflict(hosting, "Certificate", name));
            }
        }

        return Optional.empty();
    }

    private static boolean ownedByHosting(List<OwnerReference> owners, BlueMapHosting hosting) {
        String hostingUid = hosting.getMetadata().getUid();
        if (owners == null || hostingUid == null) {
            return false;
        }
        return owners.stream()
                .anyMatch(ref -> OWNER_KIND.equals(ref.getKind())
                        && Objects.equals(hosting.getMetadata().getName(), ref.getName())
                        && Objects.equals(hostingUid, ref.getUid()));
    }

    private static ConfigMap buildConfigMap(BlueMapHosting hosting, String configMapName, Map<String, String> files) {
        return new ConfigMapBuilder()
                .withNewMetadata()
                .withName(configMapName)
                .withNamespace(hosting.getMetadata().getNamespace())
                .withLabels(Labels.standard("bluemap-hosting-config", hosting.getMetadata().getName()))
                .withOwnerReferences(ownerReference(hosting))
                .endMetadata()
                .withData(files)
                .build();
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

    /**
     * SHA-256 over every generated config file's name and content, sorted by file name so the
     * result is independent of map iteration order -- see the class Javadoc's "A config change
     * must restart the pods" section. Never includes credentials: {@link
     * BlueMapConfigBuilder#buildForHosting} never writes any into the files this hashes.
     */
    private static String checksum(Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> {
                        digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                        digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
                        digest.update((byte) 0);
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * Whether an existing Deployment already matches what this reconcile would write, so the
     * write can be skipped.
     *
     * <p>This is not just an optimisation: {@code Deployment} is the one resource this reconciler
     * both writes <em>and</em> reads status back from ({@link #updateReadiness}) in the same
     * reconcile loop. Real Kubernetes ignores whatever status a client sends on a write to the
     * main (non-{@code /status}) endpoint of a resource with the status subresource enabled, so
     * writing the same content repeatedly would never actually disturb {@code status.readyReplicas}
     * there -- but skipping a genuinely no-op write is still the right instinct for an operator
     * that reconciles on every resync, not just a workaround for a test double.
     */
    private static boolean deploymentUpToDate(Deployment existing, Deployment desired) {
        if (existing == null || existing.getSpec() == null) {
            return false;
        }
        return Objects.equals(existing.getSpec().getReplicas(), desired.getSpec().getReplicas())
                && Objects.equals(existing.getSpec().getTemplate(), desired.getSpec().getTemplate());
    }

    private static void stampConfigChecksum(Deployment deployment, String checksum) {
        var templateMetadata = deployment.getSpec().getTemplate().getMetadata();
        Map<String, String> annotations = templateMetadata.getAnnotations();
        if (annotations == null) {
            annotations = new LinkedHashMap<>();
            templateMetadata.setAnnotations(annotations);
        }
        annotations.put(CONFIG_CHECKSUM_ANNOTATION, checksum);
    }

    /**
     * Reads the Deployment's current status back from the cluster and reflects readiness into
     * {@code status.url}/{@code status.ready}/the {@code Ready} condition. The URL is only ever
     * reported once the Deployment has at least as many ready replicas as {@code spec.replicas}
     * asks for -- reporting it earlier would point users at a webserver that is not actually
     * serving yet.
     */
    private UpdateControl<BlueMapHosting> updateReadiness(BlueMapHosting hosting, String namespace, String name) {
        Deployment current = client.apps().deployments().inNamespace(namespace).withName(name).get();
        int desiredReplicas = Math.max(hosting.getSpec().getReplicas(), 0);
        Integer readyReplicas =
                current == null || current.getStatus() == null ? null : current.getStatus().getReadyReplicas();
        boolean ready = desiredReplicas == 0 || (readyReplicas != null && readyReplicas >= desiredReplicas);

        if (ready) {
            hosting.getStatus().setReady(true);
            hosting.getStatus().setUrl("https://" + hosting.getSpec().getHostname());
            Conditions.set(
                    hosting.getStatus().getConditions(),
                    Conditions.ready(true, HOSTING_READY_REASON, "hosting webserver is ready"));
            return UpdateControl.patchStatus(hosting);
        }

        hosting.getStatus().setReady(false);
        hosting.getStatus().setUrl(null);
        Conditions.set(
                hosting.getStatus().getConditions(),
                Conditions.ready(
                        false, DEPLOYMENT_NOT_READY_REASON, "waiting for the hosting deployment to become ready"));
        return UpdateControl.patchStatus(hosting).rescheduleAfter(RECHECK_INTERVAL);
    }

    /**
     * Reports a blocking condition without creating or updating anything, rescheduling so a
     * fixable external cause (the tenant gets its {@code allowedDomains} set, the map's bucket
     * gets bound, cert-manager comes up, ...) is retried instead of requiring a manual nudge.
     */
    private static UpdateControl<BlueMapHosting> pending(BlueMapHosting hosting, String reason, String message) {
        hosting.getStatus().setReady(false);
        hosting.getStatus().setUrl(null);
        Conditions.set(hosting.getStatus().getConditions(), Conditions.ready(false, reason, message));
        return UpdateControl.patchStatus(hosting).rescheduleAfter(RECHECK_INTERVAL);
    }

    /**
     * Aborts the reconciliation with a {@code ResourceConflict} condition, naming the resource
     * that already exists but is not owned by this hosting. Nothing further is created or
     * updated -- see {@code TenantReconciler}'s identical {@code conflict()} method.
     */
    private static UpdateControl<BlueMapHosting> conflict(BlueMapHosting hosting, String resourceKind, String resourceName) {
        hosting.getStatus().setReady(false);
        hosting.getStatus().setUrl(null);
        Conditions.set(
                hosting.getStatus().getConditions(),
                Conditions.ready(
                        false,
                        RESOURCE_CONFLICT_REASON,
                        "existing " + resourceKind + " '" + resourceName
                                + "' is not owned by this hosting; refusing to adopt it"));
        return UpdateControl.patchStatus(hosting);
    }
}
