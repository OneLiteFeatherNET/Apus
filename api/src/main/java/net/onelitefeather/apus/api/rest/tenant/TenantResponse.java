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
package net.onelitefeather.apus.api.rest.tenant;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import net.onelitefeather.apus.api.rest.support.ConditionResponse;
import net.onelitefeather.apus.operator.api.Tenant;

/**
 * A {@link Tenant}, as the {@code platform-admin}-only {@code /api/tenants} endpoints expose it.
 * Its own type, not the custom resource itself -- {@code Tenant} carries a finalizer,
 * {@code resourceVersion}, and other managed fields that are the operator's business, not an
 * API consumer's, and would change shape with every CRD revision if reused directly here.
 *
 * <p>{@code redirectUris} is the one field here that is not merely informational: it names the
 * two URIs an administrator still has to register with the identity provider before anyone can
 * sign in to that tenant's own application instance. The operator cannot register them, and a
 * missing registration fails at sign-in with {@code AADSTS50011} and leaves nothing in the
 * cluster's logs -- so it has to reach the console, where the person who created the tenant is
 * standing. Empty for a tenant with no instance, never null.
 */
@Serdeable
public record TenantResponse(
        String name,
        String displayName,
        StorageResponse storage,
        List<String> allowedHostingDomains,
        List<PolicyEntryResponse> policy,
        String namespace,
        String objectStoreUser,
        Long storageUsedBytes,
        List<String> redirectUris,
        List<ConditionResponse> conditions) {

    public static TenantResponse from(Tenant tenant) {
        var spec = tenant.getSpec();
        var status = tenant.getStatus();
        return new TenantResponse(
                tenant.getMetadata().getName(),
                spec.getDisplayName(),
                new StorageResponse(spec.getStorage().getQuota(), spec.getStorage().getMaxObjects()),
                List.copyOf(spec.getHosting().getAllowedDomains()),
                spec.getPolicy().stream().map(PolicyEntryResponse::from).toList(),
                status.getNamespace(),
                status.getObjectStoreUser(),
                status.getStorageUsedBytes(),
                List.copyOf(status.getRedirectUris()),
                status.getConditions().stream().map(ConditionResponse::from).toList());
    }

    /** The tenant's storage quota -- never {@code storageUsedBytes}' Ceph credentials. */
    @Serdeable
    public record StorageResponse(String quota, Long maxObjects) {}
}
