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
package net.onelitefeather.apus.api.rest.directory;

import io.micronaut.serde.annotation.Serdeable;

/** What the directory endpoints accept. */
public final class DirectoryRequests {

    private DirectoryRequests() {}

    /** Create a team inside a tenant's group. */
    @Serdeable
    public record CreateTeamRequest(String displayName) {}

    /**
     * Invite somebody into a tenant's group.
     *
     * @param email the address the invitation goes to
     * @param displayName what to call them; optional, and the local part of the address is used
     *     when it is absent rather than leaving a blank row in every list
     */
    @Serdeable
    public record InviteUserRequest(String email, String displayName) {}
}
