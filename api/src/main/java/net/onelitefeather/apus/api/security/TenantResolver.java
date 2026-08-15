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
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Maps a caller to the single namespace it may act in. This is the only place in the {@code
 * api} module allowed to turn a tenant name into a namespace, and the namespace always comes
 * from {@link ApusPrincipal#tenant()} -- never from a request path, query parameter, or request
 * body. {@link #namespaceFor(ApusPrincipal)} is deliberately the only public method this class
 * has: there is no overload that accepts a namespace, a tenant name, or any other value a
 * caller could supply, because any such parameter would be exactly the cross-tenant hole design
 * spec §10.3 exists to close (see the class Javadoc on why this matters and
 * TenantResolverTest#namespaceForHasExactlyOnePublicMethod, which fails the build the moment a
 * second entry point is added).
 *
 * <p>The naming convention ({@code "bluemap-" + tenant}) mirrors {@code
 * net.onelitefeather.apus.operator.tenant.TenantReconciler#namespaceFor(Tenant)} exactly --
 * TenantResolverTest asserts the two never drift apart by calling the reconciler's own method,
 * rather than importing it into production code here. The reconciler class itself is not a
 * dependency of this class: it implements JOSDK's {@code Reconciler}, and pulling that
 * interface's dependency chain into a REST/SSE API module (which does not reconcile anything)
 * for the sake of one static method would be the wrong trade.
 */
@Singleton
public final class TenantResolver {

    /** Must match {@code TenantReconciler.namespaceFor}'s prefix -- see the class Javadoc. */
    private static final Logger LOGGER = LoggerFactory.getLogger(TenantResolver.class);

    private static final String NAMESPACE_PREFIX = "bluemap-";

    /**
     * @param principal the caller, taken from the validated token and nothing else
     * @return the namespace {@code principal} may act in
     * @throws ForbiddenException when {@code principal} has no tenant -- there is no default
     *     tenant a token without one falls back to
     */
    public String namespaceFor(ApusPrincipal principal) {
        Objects.requireNonNull(principal, "principal must not be null");
        String tenant = principal.tenant();
        if (tenant == null) {
            // Logged here rather than only at each controller so the reason is visible even for
            // the call sites that translate this into an HTTP status without a message.
            LOGGER.warn("principal '{}' carries no tenant claim", principal.subject());
            throw new ForbiddenException(
                    "principal '" + principal.subject() + "' carries no tenant claim; there is no default tenant");
        }
        return NAMESPACE_PREFIX + tenant;
    }
}
