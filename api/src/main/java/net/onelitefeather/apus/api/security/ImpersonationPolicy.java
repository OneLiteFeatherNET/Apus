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

import jakarta.inject.Singleton;
import java.util.Set;

/**
 * Who may act as whom, and with what.
 *
 * <p>Impersonation exists so an administrator can see what a tenant sees -- a support question
 * that is otherwise answered by guessing. It is also, obviously, a way to act with somebody
 * else's authority, so the whole design rests on one rule:
 *
 * <p><b>Impersonation only ever narrows.</b> The effective principal never holds {@link
 * Role#PLATFORM_ADMIN}, and never holds a role its real caller does not. There is no combination
 * of headers that lets anyone do something they could not already do as themselves; the only
 * thing it changes is <em>which tenant</em> they are doing it in. That is what makes this
 * feature's blast radius the same as its caller's, and it is why the policy strips the platform
 * role rather than checking for its absence.
 *
 * <p>Pure and separate from the filter that applies it, because this is the part worth reading
 * carefully and the part every test aims at.
 */
@Singleton
public class ImpersonationPolicy {

    /**
     * Resolves the principal a request should be served as.
     *
     * @param real who actually authenticated
     * @param targetTenant the tenant to act within; required
     * @param targetSubject the person to appear as, or {@code null} to act as the tenant itself
     *     ("as org admin") rather than as a named member
     * @return the effective principal, always a narrowing of {@code real}'s authority
     * @throws ForbiddenException when the caller may not act in that tenant at all
     */
    public Impersonation resolve(ApusPrincipal real, String targetTenant, String targetSubject) {
        if (targetTenant == null || targetTenant.isBlank()) {
            throw new ForbiddenException("impersonation needs a tenant to act in");
        }

        boolean allowed = real.isPlatformAdmin()
                // A tenant-owner may act within their own tenant -- that is the "org admin" case,
                // and it grants nothing they did not already have there.
                || (real.roles().contains(Role.TENANT_OWNER) && targetTenant.equals(real.tenant()));
        if (!allowed) {
            throw new ForbiddenException("not allowed to act within tenant '" + targetTenant + "'");
        }

        // Tenant-owner and no more. Never PLATFORM_ADMIN: an impersonated session that carried
        // the platform role would let someone reach every other tenant while wearing a tenant
        // member's name, which is the exact opposite of what an audit trail is for.
        Set<Role> effectiveRoles = Set.of(Role.TENANT_OWNER);

        String subject = targetSubject == null || targetSubject.isBlank() ? real.subject() : targetSubject;
        return new Impersonation(real.subject(), new ApusPrincipal(subject, targetTenant, effectiveRoles));
    }
}
