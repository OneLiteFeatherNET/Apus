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
package net.onelitefeather.apus.api.directory;

import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.inject.Singleton;
import net.onelitefeather.apus.api.rest.tenant.TenantRepository;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps {@link TenantGroupIndex} current, and hands the same set of groups to both of its
 * consumers: {@link PrincipalResolver}, which decides which tenant a signed-in user is in, and
 * {@link DirectoryGuard}, which decides which groups Apus may act on.
 *
 * <p>Those two must be the same set. If they ever diverged, one of them would be wider than the
 * other -- either recognising members of a group Apus refuses to manage, or managing a group
 * whose members it does not recognise. Handing both from one place is what makes divergence
 * impossible rather than merely unlikely.
 *
 * <p>Polled rather than watched. A tenant's group id changes about as often as a tenant is
 * created, the list is small, and a poll cannot get stuck half-subscribed the way a watch can --
 * the failure mode of a stalled watch here would be members silently failing to resolve, with
 * nothing obviously broken to look at.
 *
 * <p><b>A failed refresh leaves the previous index in place.</b> Not an empty one: the Kubernetes
 * API being briefly unreachable must not log everybody out of their tenant. The very first load
 * failing does leave the index empty, and that is correct -- there is nothing else it could
 * honestly be.
 */
@Singleton
public class TenantGroupIndexLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(TenantGroupIndexLoader.class);

    private final TenantRepository tenants;
    private final PrincipalResolver principals;
    private final DirectoryGuard guard;

    public TenantGroupIndexLoader(TenantRepository tenants, PrincipalResolver principals, DirectoryGuard guard) {
        this.tenants = tenants;
        this.principals = principals;
        this.guard = guard;
        refresh();
    }

    /** Rebuilds the index from the current tenant list and publishes it to both consumers. */
    @Scheduled(fixedDelay = "60s")
    public final void refresh() {
        try {
            TenantGroupIndex index = TenantGroupIndex.of(tenants.list());
            principals.setGroupIndex(index);
            guard.setManagedGroups(index.managedGroups());
            LOGGER.debug("tenant group index refreshed: {} managed group(s)", index.managedGroups().size());
        } catch (RuntimeException e) {
            // Keep serving with what we had. Losing the index would sign everybody out of their
            // tenant over a transient API-server hiccup.
            LOGGER.warn("could not refresh the tenant group index; keeping the previous one", e);
        }
    }
}
