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

import jakarta.inject.Singleton;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.Role;

/**
 * The narrowing that Microsoft Graph itself does not offer.
 *
 * <p>The permissions behind the directory operations -- {@code Group.ReadWrite.All}, {@code
 * User.ReadWrite.All}, {@code User.Invite.All} -- are directory-wide. Entra has no "these groups
 * only" variant of any of them, so the credential this API holds could rename any group in the
 * organisation and reset the password of any account in it, including accounts that have nothing
 * to do with Apus. Nothing outside this class limits that.
 *
 * <p>So the limit lives here, as a guard the operations <em>call</em> rather than a rule each of
 * them is expected to remember, and it is closed by default: an instance that has not been told
 * which groups are managed refuses everything. That is deliberately also its state in the first
 * moments after startup and its state if the tenant index ever fails to load -- failing shut is
 * the only acceptable direction for a decision of this weight.
 *
 * <p>Pure: no network call, no repository lookup. A guard that has to reach out to decide is a
 * guard that fails open when the network does, which is exactly when it matters most. Everything
 * it needs is either handed to it ({@link DirectoryUser#privilegedRoles()}) or set once by the
 * index that watches {@code Tenant} resources.
 */
@Singleton
public class DirectoryGuard {

    /**
     * Directory roles that grant power over the directory itself. Someone holding any of these
     * must never have their password reset through Apus: a tenant-owner who could do that would
     * be one console button away from owning the whole organisation.
     *
     * <p>Compared case-insensitively, and the list is deliberately generous -- a role that turns
     * out not to be dangerous costs an administrator one manual password reset in Entra, whereas
     * one missing from the list costs the directory.
     */
    private static final Set<String> PRIVILEGED_ROLES = Set.of(
            "global administrator",
            "company administrator",
            "privileged role administrator",
            "privileged authentication administrator",
            "user administrator",
            "authentication administrator",
            "helpdesk administrator",
            "password administrator",
            "security administrator",
            "conditional access administrator",
            "application administrator",
            "cloud application administrator",
            "directory writers",
            "partner tier1 support",
            "partner tier2 support");

    /**
     * The groups some {@code Tenant} currently claims via {@code spec.identity.groupId}. Held in
     * an {@link AtomicReference} because it is replaced wholesale by the tenant index on a
     * watch event while requests are reading it; an immutable set swapped atomically means a
     * reader either sees the whole old set or the whole new one, never a half-updated one.
     */
    private final AtomicReference<Set<String>> managedGroups = new AtomicReference<>(Set.of());

    /** Replaces the managed-group set. Called by the tenant index, not by request handling. */
    public void setManagedGroups(Set<String> groups) {
        managedGroups.set(groups == null ? Set.of() : Set.copyOf(groups));
    }

    /** The groups currently considered Apus's to touch. */
    public Set<String> managedGroups() {
        return managedGroups.get();
    }

    /**
     * Refuses any group that no {@code Tenant} claims -- including a blank or absent one, since
     * an unconfigured tenant must not end up the widest on the platform rather than the
     * narrowest.
     */
    public void requireManagedGroup(ApusPrincipal principal, String groupId) {
        Objects.requireNonNull(principal, "principal must not be null");
        if (groupId == null || groupId.isBlank()) {
            throw new ForbiddenException("this tenant has no identity group configured");
        }
        if (!managedGroups.get().contains(groupId)) {
            throw new ForbiddenException("group '" + groupId + "' is not managed by Apus");
        }
    }

    /**
     * Read access to one tenant's directory: the group must be managed, and the caller must be a
     * platform admin or a member of that very tenant.
     */
    public void requireTenantAccess(ApusPrincipal principal, String tenant, String groupId) {
        requireManagedGroup(principal, groupId);
        if (principal.isPlatformAdmin()) {
            return;
        }
        if (principal.tenant() == null || !principal.tenant().equals(tenant)) {
            throw new ForbiddenException("not a member of tenant '" + tenant + "'");
        }
    }

    /**
     * Write access to one tenant's directory. Everything {@link #requireTenantAccess} demands,
     * plus a role that may actually change things -- a viewer reads, and these operations do not
     * read.
     */
    public void requireTenantWrite(ApusPrincipal principal, String tenant, String groupId) {
        requireTenantAccess(principal, tenant, groupId);
        if (principal.isPlatformAdmin()) {
            return;
        }
        if (!principal.roles().contains(Role.TENANT_OWNER)) {
            throw new ForbiddenException("changing a tenant's directory requires the tenant-owner role");
        }
    }

    /**
     * The last check before a password is reset, and the one worth reading twice.
     *
     * <p>Refuses a target holding any {@link #PRIVILEGED_ROLES} role, because a tenant-owner able
     * to reset a directory administrator's password owns the organisation. Refuses the caller's
     * own account too -- a self-service password change belongs at the identity provider, where
     * it is challenged; this permission exists for helping somebody else.
     */
    public void requirePasswordResetAllowed(ApusPrincipal principal, DirectoryUser target) {
        Objects.requireNonNull(target, "target must not be null");
        if (target.privilegedRoles().stream()
                .map(role -> role.toLowerCase(Locale.ROOT))
                .anyMatch(PRIVILEGED_ROLES::contains)) {
            throw new ForbiddenException(
                    "refusing to reset the password of a privileged directory account (" + target.id() + ")");
        }
        if (target.id().equals(principal.subject())) {
            throw new ForbiddenException("reset your own password at the identity provider, not here");
        }
    }
}
