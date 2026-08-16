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
import java.util.List;
import net.onelitefeather.apus.api.directory.DirectoryTeam;
import net.onelitefeather.apus.api.directory.DirectoryUser;

/** What the directory endpoints return. Kept together because they are read together. */
public final class DirectoryResponses {

    private DirectoryResponses() {}

    /**
     * A team.
     *
     * @param memberCount {@code null} rather than {@code 0} when the directory could be asked for
     *     the team but not for its size -- a zero meaning "not counted" is a lie somebody would
     *     act on, and {@code null} is the one value a UI cannot mistake for a number
     */
    @Serdeable
    public record TeamResponse(String id, String displayName, Integer memberCount) {
        public static TeamResponse from(DirectoryTeam team) {
            return new TeamResponse(team.id(), team.displayName(), team.hasMemberCount() ? team.memberCount() : null);
        }
    }

    /**
     * A person.
     *
     * @param privileged whether this account holds a directory role that makes it un-resettable
     *     through Apus. Exposed so the console can grey the button out rather than let someone
     *     press it and receive a refusal -- the refusal is still what actually enforces it
     */
    @Serdeable
    public record UserResponse(String id, String displayName, String email, boolean privileged) {
        public static UserResponse from(DirectoryUser user) {
            return new UserResponse(user.id(), user.displayName(), user.email(), user.isPrivileged());
        }
    }

    /**
     * The counts shown next to a tenant.
     *
     * @param teams number of teams, or {@code null} when the directory could not be asked
     * @param users number of members, or {@code null} when the directory could not be asked
     * @param unavailableReason why they are {@code null}, in words an administrator can act on.
     *     Present exactly when a count is missing -- a UI showing "unavailable" with no reason
     *     sends someone to read server logs for something the server already knew
     */
    @Serdeable
    public record DirectoryCountsResponse(Integer teams, Integer users, String unavailableReason) {
        public static DirectoryCountsResponse of(int teams, int users) {
            return new DirectoryCountsResponse(teams, users, null);
        }

        public static DirectoryCountsResponse unavailable(String reason) {
            return new DirectoryCountsResponse(null, null, reason);
        }
    }

    /** The teams and members of one tenant, with the same "unavailable is not empty" rule. */
    @Serdeable
    public record TenantDirectoryResponse(
            List<TeamResponse> teams, List<UserResponse> users, String unavailableReason) {}

    /**
     * The result of a password reset.
     *
     * <p>{@code temporaryPassword} is the only place this value ever exists outside the identity
     * provider. It is shown to a human once and is deliberately not stored, not logged, and not
     * retrievable again -- the same rule the tenant push token follows.
     */
    @Serdeable
    public record PasswordResetResponse(String userId, String temporaryPassword) {}
}
