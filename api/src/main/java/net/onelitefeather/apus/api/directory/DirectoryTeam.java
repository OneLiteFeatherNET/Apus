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

/**
 * A team within a tenant: a group nested inside the tenant's own group.
 *
 * @param id the directory's object id
 * @param displayName what to show a human
 * @param memberCount how many people are in it, or {@code -1} when the directory could be asked
 *     for the team but not for its size. Deliberately not {@code 0}: a zero that means "we could
 *     not count" is a lie an administrator would act on
 */
public record DirectoryTeam(String id, String displayName, int memberCount) {

    /** Sentinel for {@link #memberCount} when the count could not be obtained. */
    public static final int COUNT_UNAVAILABLE = -1;

    public DirectoryTeam {
        Objects.requireNonNull(id, "id must not be null");
    }

    /** Whether {@link #memberCount} is a real count rather than {@link #COUNT_UNAVAILABLE}. */
    public boolean hasMemberCount() {
        return memberCount >= 0;
    }
}
