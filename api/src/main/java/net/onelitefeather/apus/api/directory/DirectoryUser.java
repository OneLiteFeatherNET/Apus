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

import java.util.Objects;
import java.util.Set;

/**
 * A person, as the directory reports them. Deliberately small: an identifier to act on, enough to
 * show a human who this is, and the one fact the guard needs to make a decision.
 *
 * @param id the directory's own object id, the only value any operation is addressed by
 * @param displayName what to show a human; may be blank in a directory that has none
 * @param email the sign-in address, also blank-able -- an invited user has one before they have
 *     anything else
 * @param privilegedRoles the directory roles this user holds that grant power over the directory
 *     itself (Global Administrator and friends). Empty for almost everyone. Carried on the user
 *     rather than looked up at decision time so {@link DirectoryGuard} can stay a pure function
 *     -- a guard that has to make a network call to decide is a guard that fails open when the
 *     network does
 */
public record DirectoryUser(String id, String displayName, String email, Set<String> privilegedRoles) {

    public DirectoryUser {
        Objects.requireNonNull(id, "id must not be null");
        privilegedRoles = privilegedRoles == null ? Set.of() : Set.copyOf(privilegedRoles);
    }

    /** Convenience for the common case: an ordinary member with no directory power at all. */
    public static DirectoryUser member(String id, String displayName, String email) {
        return new DirectoryUser(id, displayName, email, Set.of());
    }

    /** Whether this user holds any role that grants power over the directory itself. */
    public boolean isPrivileged() {
        return !privilegedRoles.isEmpty();
    }
}
