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
package net.onelitefeather.apus.operator.tenant;

import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.Map;
import java.util.Objects;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.Conditions;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.rook.CephObjectStoreUser;

/**
 * Turns a Tenant into the ground a tenant stands on: a namespace, compute limits and a Ceph
 * user carrying the storage quota.
 *
 * <p>The storage limit is deliberately enforced by Ceph rather than by this operator — a
 * tenant cannot exceed it even if Apus miscounts.
 *
 * <p>Resources are applied via {@code createOr(NonDeletingOperation::update)} rather than
 * {@code serverSideApply()}: the fabric8 Kubernetes mock server used in tests does not support
 * the server-side-apply PATCH verb (it 404s on a resource that does not exist yet), so this
 * get-then-create-or-update semantics is used instead. It is idempotent the same way apply is.
 *
 * <p><b>Cross-tenant safety:</b> both the namespace ({@code bluemap-<name>}) and the Ceph
 * object-store user ({@code apus-<name>}) are named deterministically from the tenant name
 * alone. A tenant name can be reused after the original tenant is deleted, and a namespace
 * could already exist for unrelated reasons before a tenant is even created. Naming alone is
 * therefore not enough to prove ownership. Every resource this reconciler creates is stamped
 * with the tenant's name <em>and</em> UID ({@link Labels#TENANT}, {@link Labels#TENANT_UID});
 * before touching a resource that already exists, both labels are checked against the tenant
 * currently being reconciled. A mismatch (or missing labels) aborts the reconciliation with a
 * {@code ResourceConflict} condition instead of silently adopting -- and thereby leaking the
 * contents of -- someone else's namespace or storage user.
 */
@ControllerConfiguration
public class TenantReconciler implements Reconciler<Tenant> {

    public static final String TENANT_LABEL = Labels.TENANT;
    public static final String TENANT_UID_LABEL = Labels.TENANT_UID;

    /** Reason set on the {@code Ready} condition when an existing resource fails the ownership check. */
    public static final String RESOURCE_CONFLICT_REASON = "ResourceConflict";

    private static final String TENANT_API_VERSION = "bluemap.onelitefeather.net/v1alpha1";
    private static final String TENANT_KIND = "Tenant";

    private final KubernetesClient client;
    private final OperatorConfig config;

    public TenantReconciler(KubernetesClient client, OperatorConfig config) {
        this.client = client;
        this.config = config;
    }

    /** The namespace every namespaced resource of this tenant lives in: {@code bluemap-<name>}. */
    public static String namespaceFor(Tenant tenant) {
        return "bluemap-" + tenant.getMetadata().getName();
    }

    /** The Ceph object store user carrying this tenant's storage quota: {@code apus-<name>}. */
    public static String cephUserFor(Tenant tenant) {
        return "apus-" + tenant.getMetadata().getName();
    }

    @Override
    public UpdateControl<Tenant> reconcile(Tenant tenant, Context<Tenant> context) {
        String namespace = namespaceFor(tenant);
        String cephUser = cephUserFor(tenant);
        String tenantName = tenant.getMetadata().getName();
        String tenantUid = tenant.getMetadata().getUid();

        Namespace existingNamespace = client.namespaces().withName(namespace).get();
        if (existingNamespace != null
                && !ownedBySameTenant(existingNamespace.getMetadata().getLabels(), tenantName, tenantUid)) {
            return conflict(tenant, "Namespace", namespace);
        }

        CephObjectStoreUser existingUser = client.resources(CephObjectStoreUser.class)
                .inNamespace(config.rookNamespace())
                .withName(cephUser)
                .get();
        if (existingUser != null
                && !ownedBySameTenant(existingUser.getMetadata().getLabels(), tenantName, tenantUid)) {
            return conflict(tenant, "CephObjectStoreUser", cephUser);
        }

        OwnerReference ownerReference = tenantOwnerReference(tenant);

        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(namespace)
                        .withLabels(tenantLabels(tenantName, tenantUid))
                        .withOwnerReferences(ownerReference)
                        .endMetadata()
                        .build())
                .createOr(NonDeletingOperation::update);

        client.resourceQuotas()
                .inNamespace(namespace)
                .resource(new ResourceQuotaBuilder()
                        .withNewMetadata()
                        .withName("apus-tenant")
                        .withNamespace(namespace)
                        .withLabels(tenantLabels(tenantName, tenantUid))
                        .withOwnerReferences(ownerReference)
                        .endMetadata()
                        .withNewSpec()
                        .withHard(Map.of(
                                "requests.cpu", new Quantity("4"),
                                "requests.memory", new Quantity("8Gi")))
                        .endSpec()
                        .build())
                .createOr(NonDeletingOperation::update);

        client.limitRanges()
                .inNamespace(namespace)
                .resource(new LimitRangeBuilder()
                        .withNewMetadata()
                        .withName("apus-tenant")
                        .withNamespace(namespace)
                        .withLabels(tenantLabels(tenantName, tenantUid))
                        .withOwnerReferences(ownerReference)
                        .endMetadata()
                        .build())
                .createOr(NonDeletingOperation::update);

        CephObjectStoreUser user = new CephObjectStoreUser();
        user.getMetadata().setName(cephUser);
        user.getMetadata().setNamespace(config.rookNamespace());
        user.getMetadata().setLabels(tenantLabels(tenantName, tenantUid));
        user.getSpec().setStore(config.cephObjectStore());
        user.getSpec().setDisplayName(cephUser);
        user.getSpec().getQuotas().setMaxSize(tenant.getSpec().getStorage().getQuota());
        user.getSpec().getQuotas().setMaxObjects(tenant.getSpec().getStorage().getMaxObjects());
        // No ownerReference here: the user lives in the Rook namespace, not the tenant's own
        // namespace, and Kubernetes garbage collection of a namespaced dependent owned by a
        // cluster-scoped resource across namespaces is not something this operator relies on.
        // The tenant/UID labels checked above are what actually prevents cross-tenant reuse.
        client.resources(CephObjectStoreUser.class)
                .inNamespace(config.rookNamespace())
                .resource(user)
                .createOr(NonDeletingOperation::update);

        tenant.getStatus().setNamespace(namespace);
        tenant.getStatus().setObjectStoreUser(cephUser);
        Conditions.set(
                tenant.getStatus().getConditions(),
                Conditions.ready(true, "Provisioned", "namespace and storage user exist"));

        return UpdateControl.patchStatus(tenant);
    }

    /**
     * Checks whether an existing resource's labels identify it as already belonging to the
     * tenant currently being reconciled. Both the name and the UID label must match: the name
     * alone is not enough, since a tenant name can be reused after deletion.
     */
    private static boolean ownedBySameTenant(Map<String, String> labels, String tenantName, String tenantUid) {
        if (labels == null || tenantUid == null) {
            return false;
        }
        return Objects.equals(tenantName, labels.get(Labels.TENANT))
                && Objects.equals(tenantUid, labels.get(Labels.TENANT_UID));
    }

    /**
     * Aborts the reconciliation with a {@code ResourceConflict} condition, naming the resource
     * that already exists but is not owned by this tenant. Nothing further is created or
     * updated -- a clear failure is far better than silently adopting (and thereby leaking the
     * contents of) someone else's resource.
     */
    private static UpdateControl<Tenant> conflict(Tenant tenant, String resourceKind, String resourceName) {
        Conditions.set(
                tenant.getStatus().getConditions(),
                Conditions.ready(
                        false,
                        RESOURCE_CONFLICT_REASON,
                        "existing " + resourceKind + " '" + resourceName
                                + "' is not labelled as owned by this tenant; refusing to adopt it"));
        return UpdateControl.patchStatus(tenant);
    }

    private static Map<String, String> tenantLabels(String tenantName, String tenantUid) {
        Map<String, String> labels = Labels.standard("tenant", tenantName);
        labels.put(Labels.TENANT, tenantName);
        if (tenantUid != null && !tenantUid.isBlank()) {
            labels.put(Labels.TENANT_UID, tenantUid);
        }
        return labels;
    }

    /** Tenant is cluster-scoped, so a namespace (also cluster-scoped) can safely be owned by it. */
    private static OwnerReference tenantOwnerReference(Tenant tenant) {
        return new OwnerReferenceBuilder()
                .withApiVersion(TENANT_API_VERSION)
                .withKind(TENANT_KIND)
                .withName(tenant.getMetadata().getName())
                .withUid(tenant.getMetadata().getUid())
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }
}
