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
package net.onelitefeather.apus.operator.map;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.opentelemetry.api.common.Attributes;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.rook.ObjectBucketClaim;
import net.onelitefeather.apus.operator.telemetry.Tracing;
import net.onelitefeather.apus.operator.tenant.TenantReconciler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns a {@link BlueMapMap} into a bound S3 bucket, and mirrors that bucket's identity into
 * {@code status.bucket} so {@link net.onelitefeather.apus.operator.render.RenderJobBuilder} has
 * something to read.
 *
 * <p><b>Why this class exists despite not being in the original task breakdown:</b> the plan
 * registers a {@code BlueMapMapReconciler} in the operator entrypoint (Task 7) but never
 * actually specified one -- a gap discovered while implementing Task 6. Without it, {@code
 * BlueMapMap} resources would never provision a bucket at all, and {@code BlueMapRenderReconciler}
 * would have nothing bound to wait for.
 *
 * <p><b>Bucket provisioning is delegated to {@link BucketProvisioner}</b> (Task 4), which
 * creates the Rook {@link ObjectBucketClaim} and reports back once Rook has bound it. This class
 * adds three things {@code BucketProvisioner} deliberately does not do on its own: the
 * ownership check on a pre-existing claim (see below), deriving the Ceph object-store user from
 * the map's namespace, and copying the bound claim's identity into {@code BlueMapMap.status}.
 *
 * <p><b>Deriving the tenant from the map's namespace:</b> a {@link BlueMapMap} carries no direct
 * reference to its owning {@link Tenant} -- only the namespace it lives in, which {@link
 * TenantReconciler#namespaceFor(Tenant)} names deterministically as {@code bluemap-<tenant>}.
 * This reconciler inverts that convention (stripping the {@value #TENANT_NAMESPACE_PREFIX}
 * prefix back off) to recover the tenant name, then routes it back through {@link
 * TenantReconciler#cephUserFor(Tenant)} via a minimal synthetic {@link Tenant} carrying just
 * that name -- so the actual {@code "apus-<name>"} naming convention itself still lives in
 * exactly one place rather than being duplicated here.
 *
 * <p><b>Cross-map safety:</b> the bucket claim is named after the map alone ({@code
 * BucketProvisioner} uses {@code map.getMetadata().getName()}), so a map name can be reused
 * after the original map is deleted, and nothing stops a claim of that name existing for
 * unrelated reasons. Exactly like {@link TenantReconciler}, every claim {@link BucketProvisioner}
 * creates is stamped with the map's name <em>and</em> UID ({@link Labels#MAP}, {@link
 * Labels#MAP_UID}); before this reconciler ever treats an existing claim as its own, both
 * labels are checked against the map currently being reconciled. A mismatch (or missing labels)
 * aborts the reconciliation with a {@code ResourceConflict} condition instead of silently
 * reporting -- and thereby leaking access to -- someone else's bucket.
 *
 * <p><b>No render may start against an unbound map:</b> until Rook has bound the claim <em>and</em>
 * published its endpoint, {@code status.bucket.name} is left empty on purpose.
 * {@code BlueMapRenderReconciler} treats an empty bucket name as "not ready yet" and refuses to
 * submit a render job for it.
 *
 * <p><b>Rook not (yet) installed:</b> mirrors {@code TenantReconciler}'s handling of {@code
 * CephObjectStoreUser}. {@link #reconcile} checks {@link
 * io.fabric8.kubernetes.client.Client#supports(Class)} for {@link ObjectBucketClaim} before this
 * class -- or {@link BucketProvisioner} on its behalf -- ever touches one. If Rook's {@code
 * ObjectBucketClaim} CRD is not registered on the cluster, nothing is read or written; the
 * {@code Ready} condition is set to {@code False} with reason {@value #ROOK_UNAVAILABLE_REASON}
 * instead of the reconciler throwing, and the next resync retries once Rook is ready.
 */
@ControllerConfiguration
public class BlueMapMapReconciler implements Reconciler<BlueMapMap> {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlueMapMapReconciler.class);

    /** Reason set on the {@code Ready} condition while Rook has not yet bound the claim. */
    public static final String BUCKET_PENDING_REASON = "BucketPending";

    /** Reason set on the {@code Ready} condition once the bucket is bound and usable. */
    public static final String BUCKET_PROVISIONED_REASON = "BucketProvisioned";

    /** Reason set on the {@code Ready} condition when an existing resource fails the ownership check. */
    public static final String RESOURCE_CONFLICT_REASON = "ResourceConflict";

    /**
     * Reason set on the {@code Ready} condition when Rook's {@code ObjectBucketClaim} CRD is not
     * registered on the cluster, so no bucket could be provisioned.
     */
    public static final String ROOK_UNAVAILABLE_REASON = "RookUnavailable";

    /** See {@link TenantReconciler#namespaceFor(Tenant)} -- every tenant namespace is named this way. */
    static final String TENANT_NAMESPACE_PREFIX = "bluemap-";

    /**
     * Key Rook writes into the ConfigMap it creates alongside a bound {@link ObjectBucketClaim}
     * (same name, same namespace as the claim), carrying the RGW host to connect to.
     */
    private static final String BUCKET_HOST_KEY = "BUCKET_HOST";

    /** Key Rook writes into the same ConfigMap, carrying the RGW port. */
    private static final String BUCKET_PORT_KEY = "BUCKET_PORT";

    private static final Duration RECHECK_INTERVAL = Duration.ofSeconds(10);

    private final KubernetesClient client;
    private final BucketProvisioner bucketProvisioner;

    public BlueMapMapReconciler(KubernetesClient client, OperatorConfig config) {
        this.client = client;
        this.bucketProvisioner = new BucketProvisioner(client, config);
    }

    @Override
    public UpdateControl<BlueMapMap> reconcile(BlueMapMap map, Context<BlueMapMap> context) {
        return Tracing.reconcile("BlueMapMap", Tracing.MAP, map, () -> doReconcile(map));
    }

    /**
     * The reconciliation itself, split out of {@link #reconcile} so the span wrapping it stays a
     * single readable line rather than an extra level of indentation over the whole method.
     */
    private UpdateControl<BlueMapMap> doReconcile(BlueMapMap map) {
        String namespace = map.getMetadata().getNamespace();
        String name = map.getMetadata().getName();
        String mapUid = map.getMetadata().getUid();
        String cephUser = cephUserForNamespace(namespace);

        // Rook may not be installed yet -- see TenantReconciler's identical check for why
        // supports() rather than a get()/create() probe is used to tell "the CRD doesn't exist"
        // apart from "the object doesn't exist" (both would otherwise look like a 404).
        if (!client.supports(ObjectBucketClaim.class)) {
            return rookUnavailable(map, name);
        }

        ObjectBucketClaim existingClaim =
                client.resources(ObjectBucketClaim.class).inNamespace(namespace).withName(name).get();
        if (existingClaim != null && !ownedBySameMap(existingClaim.getMetadata().getLabels(), name, mapUid)) {
            return conflict(map, "ObjectBucketClaim", name);
        }

        // Its own span: creating the claim is one API call, but everything after it is Rook
        // taking however long it takes to actually provision a bucket -- the single step in this
        // reconciliation someone would ask "why is that map still pending" about.
        Optional<ObjectBucketClaim> bound = Tracing.step(
                "await object bucket claim",
                Attributes.of(Tracing.MAP, name, Tracing.K8S_NAMESPACE_NAME, namespace),
                () -> bucketProvisioner.ensureBucket(map, cephUser));
        if (bound.isEmpty()) {
            return pending(map, "waiting for Rook to bind the object bucket claim for map '" + name + "'");
        }

        ObjectBucketClaim claim = bound.get();
        String bucketName = claim.getSpec().getBucketName();
        String secretName = claim.getMetadata().getName();

        Optional<String> endpoint = resolveEndpoint(namespace, secretName);
        if (endpoint.isEmpty()) {
            // Rook binds the claim and writes the Secret/ConfigMap together in practice, but
            // nothing guarantees a reconciler observes both writes atomically -- treat this as
            // still-provisioning rather than reporting a bucket with no usable endpoint.
            return pending(map, "bucket '" + bucketName + "' is bound but its endpoint is not published yet");
        }

        // Only the transition is worth an info line: a bound map reconciles again on every
        // resync, and "still bound" every few minutes per map is noise, not an event.
        boolean newlyBound = !bucketName.equals(map.getStatus().getBucket().getName());
        map.getStatus().getBucket().setName(bucketName);
        map.getStatus().getBucket().setSecretName(secretName);
        map.getStatus().getBucket().setEndpoint(endpoint.get());
        if (newlyBound) {
            LOGGER.info("map '{}' in namespace '{}' is backed by bucket '{}'", name, namespace, bucketName);
        } else {
            LOGGER.debug("map '{}' is up to date (bucket '{}')", name, bucketName);
        }

        Conditions.set(
                map.getStatus().getConditions(),
                Conditions.ready(true, BUCKET_PROVISIONED_REASON, "bucket '" + bucketName + "' is bound and ready"));
        return UpdateControl.patchStatus(map);
    }

    /**
     * Recovers the Ceph object-store user for a map living in {@code namespace}, by stripping
     * the tenant-namespace prefix back off and handing the recovered tenant name to {@link
     * TenantReconciler#cephUserFor(Tenant)} through a minimal synthetic {@link Tenant}. See the
     * class Javadoc for why this indirection exists instead of just concatenating {@code
     * "apus-"} here directly.
     */
    private static String cephUserForNamespace(String namespace) {
        String tenantName = namespace != null && namespace.startsWith(TENANT_NAMESPACE_PREFIX)
                ? namespace.substring(TENANT_NAMESPACE_PREFIX.length())
                : namespace;
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName(tenantName);
        return TenantReconciler.cephUserFor(tenant);
    }

    /**
     * Reads the endpoint Rook publishes for a bound claim from the ConfigMap it writes
     * alongside the credentials Secret (same name as the claim, same namespace). Apus does not
     * model that ConfigMap as a typed resource -- unlike {@link ObjectBucketClaim}, this
     * operator never writes it, only reads two plain string keys back out of it.
     */
    private Optional<String> resolveEndpoint(String namespace, String configMapName) {
        ConfigMap configMap =
                client.configMaps().inNamespace(namespace).withName(configMapName).get();
        if (configMap == null || configMap.getData() == null) {
            return Optional.empty();
        }
        String host = configMap.getData().get(BUCKET_HOST_KEY);
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String port = configMap.getData().get(BUCKET_PORT_KEY);
        return Optional.of(port == null || port.isBlank() ? "http://" + host : "http://" + host + ":" + port);
    }

    /**
     * Checks whether an existing claim's labels identify it as already belonging to the map
     * currently being reconciled. Both the name and the UID label must match -- see the class
     * Javadoc's "Cross-map safety" section.
     */
    private static boolean ownedBySameMap(Map<String, String> labels, String mapName, String mapUid) {
        if (labels == null || mapUid == null) {
            return false;
        }
        return Objects.equals(mapName, labels.get(Labels.MAP)) && Objects.equals(mapUid, labels.get(Labels.MAP_UID));
    }

    private static UpdateControl<BlueMapMap> pending(BlueMapMap map, String message) {
        LOGGER.debug("map '{}' is not ready yet: {}", map.getMetadata().getName(), message);
        Conditions.set(map.getStatus().getConditions(), Conditions.ready(false, BUCKET_PENDING_REASON, message));
        return UpdateControl.patchStatus(map).rescheduleAfter(RECHECK_INTERVAL);
    }

    /**
     * Reports that Rook's {@code ObjectBucketClaim} CRD is not registered on the cluster instead
     * of letting a {@code get()}/{@code create()} against it throw. A missing CRD is an
     * environment that has not finished coming up yet, not a bug -- see the class Javadoc's
     * "Rook not (yet) installed" section, mirroring {@code TenantReconciler}.
     */
    private static UpdateControl<BlueMapMap> rookUnavailable(BlueMapMap map, String name) {
        LOGGER.warn(
                "map '{}': the ObjectBucketClaim CRD (objectbucket.io) is not registered on this cluster --"
                        + " Rook is not installed or not ready yet, so no bucket can be provisioned",
                name);
        Conditions.set(
                map.getStatus().getConditions(),
                Conditions.ready(
                        false,
                        ROOK_UNAVAILABLE_REASON,
                        "ObjectBucketClaim CRD (objectbucket.io) is not registered on this cluster -- Rook is"
                                + " not installed or not ready yet; cannot provision a bucket for map '" + name
                                + "'"));
        return UpdateControl.patchStatus(map).rescheduleAfter(RECHECK_INTERVAL);
    }

    /**
     * Aborts the reconciliation with a {@code ResourceConflict} condition, naming the resource
     * that already exists but is not owned by this map. Nothing further is created, updated, or
     * reported in status -- see {@link TenantReconciler}'s identical {@code conflict()} method.
     */
    private static UpdateControl<BlueMapMap> conflict(BlueMapMap map, String resourceKind, String resourceName) {
        LOGGER.warn(
                "map '{}': existing {} '{}' is not labelled as owned by this map -- refusing to adopt it",
                map.getMetadata().getName(),
                resourceKind,
                resourceName);
        Conditions.set(
                map.getStatus().getConditions(),
                Conditions.ready(
                        false,
                        RESOURCE_CONFLICT_REASON,
                        "existing " + resourceKind + " '" + resourceName
                                + "' is not labelled as owned by this map; refusing to adopt it"));
        return UpdateControl.patchStatus(map);
    }
}
