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
package net.onelitefeather.apus.api.security;

import java.util.Objects;
import java.util.Set;

/**
 * Who is calling, derived solely from the validated token -- never from anything the caller
 * supplies in a request. This is the only source {@link TenantResolver} may read a tenant from.
 *
 * <p>{@code tenant} is the organisation claim from the token (design spec §10.3) and may be
 * {@code null}: a {@code platform-admin} is not necessarily a member of any tenant. It is
 * deliberately never defaulted to a fallback value here or anywhere downstream -- a caller
 * without a tenant is a caller {@link TenantResolver} refuses to resolve a namespace for, not
 * one that silently lands in some default namespace.
 *
 * @param subject the token subject, i.e. who authenticated, for logging/auditing
 * @param tenant the organisation claim, or {@code null} if the token carries none
 * @param roles the roles granted to this caller; never {@code null}, may be empty
 */
public record ApusPrincipal(String subject, String tenant, Set<Role> roles) {

    public ApusPrincipal {
        Objects.requireNonNull(subject, "subject must not be null");
        Objects.requireNonNull(roles, "roles must not be null");
        // Defensive copy: an immutable snapshot, so a caller mutating the Set they passed in
        // (or one this record hands back via roles()) can never retroactively change what this
        // principal was authorized with.
        roles = Set.copyOf(roles);
        if (tenant != null && tenant.isBlank()) {
            tenant = null;
        }
    }

    /** Whether this caller holds the platform-wide {@link Role#PLATFORM_ADMIN} role. */
    public boolean isPlatformAdmin() {
        return roles.contains(Role.PLATFORM_ADMIN);
    }

    /**
     * Whether this caller may write within its own tenant -- {@link Role#TENANT_OWNER} or
     * {@link Role#TENANT_OPERATOR}. Deliberately excludes {@link Role#PLATFORM_ADMIN}: that
     * role's write access is to platform-level resources (tenants, quotas), not to a tenant's
     * sources/maps/renders, and excludes {@link Role#TENANT_VIEWER} by definition.
     */
    public boolean canWrite() {
        return roles.contains(Role.TENANT_OWNER) || roles.contains(Role.TENANT_OPERATOR);
    }
}
