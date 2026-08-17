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

import java.util.List;

/**
 * What Apus needs from the identity provider, and nothing more.
 *
 * <p>An interface rather than a Graph client used directly, for two reasons that both bite in
 * practice. Every controller test would otherwise need a Graph credential and a live tenant, so
 * the interesting cases -- a tenant-owner reaching into another tenant, a password reset aimed at
 * an administrator -- would be the ones nobody could write a test for. And the operations here
 * are the complete list of what the granted permissions are used for: a reader checking whether
 * {@code Group.ReadWrite.All} is being used responsibly reads this file, not a client full of
 * URLs.
 *
 * <p>Every method may throw {@link DirectoryUnavailableException}. Graph is somebody else's
 * service and it will be down or throttling at some point; callers are expected to degrade that
 * panel rather than fail the page around it.
 *
 * <p><b>Nothing here checks authorisation.</b> That is {@link DirectoryGuard}'s job, called by
 * the controller before it gets this far. An implementation that also checked would invite the
 * belief that either check alone is enough.
 */
public interface Directory {

    /** The teams (nested groups) belonging to a tenant's group. */
    List<DirectoryTeam> teamsIn(String groupId);

    /** The members of a tenant's group, each carrying whatever privileged roles they hold. */
    List<DirectoryUser> membersOf(String groupId);

    /**
     * Creates a team inside a tenant's group and returns it.
     *
     * @param groupId the tenant's group, already checked by {@link DirectoryGuard}
     * @param displayName what to call the new team
     */
    DirectoryTeam createTeam(String groupId, String displayName);

    /**
     * Invites somebody by e-mail and adds them to the tenant's group.
     *
     * @return the invited user, as the directory now knows them
     */
    DirectoryUser invite(String groupId, String email, String displayName);

    /** One user by id, or {@code null} if the directory has no such account. */
    DirectoryUser findUser(String userId);

    /**
     * Resets a password and returns the temporary one.
     *
     * <p>The returned value is shown to a human exactly once and never stored. It must not be
     * logged, put in a span attribute, or written to any resource's status -- the same rule the
     * tenant push token already follows.
     */
    String resetPassword(String userId);
}
