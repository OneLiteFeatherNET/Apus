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
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.NonDeletingOperation;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.ControllerConfiguration;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import java.util.Map;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.Conditions;
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
 */
@ControllerConfiguration
public class TenantReconciler implements Reconciler<Tenant> {

    public static final String TENANT_LABEL = "apus.onelitefeather.net/tenant";

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

        client.namespaces()
                .resource(new NamespaceBuilder()
                        .withNewMetadata()
                        .withName(namespace)
                        .withLabels(Map.of(TENANT_LABEL, tenant.getMetadata().getName()))
                        .endMetadata()
                        .build())
                .createOr(NonDeletingOperation::update);

        client.resourceQuotas()
                .inNamespace(namespace)
                .resource(new ResourceQuotaBuilder()
                        .withNewMetadata()
                        .withName("apus-tenant")
                        .withNamespace(namespace)
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
                        .endMetadata()
                        .build())
                .createOr(NonDeletingOperation::update);

        CephObjectStoreUser user = new CephObjectStoreUser();
        user.getMetadata().setName(cephUser);
        user.getMetadata().setNamespace(config.rookNamespace());
        user.getSpec().setStore(config.cephObjectStore());
        user.getSpec().setDisplayName(cephUser);
        user.getSpec().getQuotas().setMaxSize(tenant.getSpec().getStorage().getQuota());
        user.getSpec().getQuotas().setMaxObjects(tenant.getSpec().getStorage().getMaxObjects());
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
}
