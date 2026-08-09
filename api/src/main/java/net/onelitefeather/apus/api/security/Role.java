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

import java.util.Locale;
import java.util.Optional;

/**
 * The four roles defined by design spec §10.3. There is no fifth, implicit "no role" default:
 * a token that carries none of these grants no permission at all.
 *
 * <table>
 *     <caption>Role capabilities, from §10.3</caption>
 *     <tr><th>Role</th><th>May</th></tr>
 *     <tr><td>{@link #PLATFORM_ADMIN}</td>
 *         <td>create/change/delete tenants, quotas, cluster-wide view</td></tr>
 *     <tr><td>{@link #TENANT_OWNER}</td><td>everything in its own tenant, including members</td></tr>
 *     <tr><td>{@link #TENANT_OPERATOR}</td><td>maintain sources and maps, trigger renders</td></tr>
 *     <tr><td>{@link #TENANT_VIEWER}</td><td>read only</td></tr>
 * </table>
 */
public enum Role {
    PLATFORM_ADMIN,
    TENANT_OWNER,
    TENANT_OPERATOR,
    TENANT_VIEWER;

    /**
     * Parses a role claim value as it appears in a token (kebab-case, e.g. {@code
     * "platform-admin"}) into a {@link Role}. Unknown values -- a role the identity broker
     * knows about but Apus does not (yet) -- resolve to {@link Optional#empty()} rather than
     * throwing, so that one unrecognised entry in a roles claim does not reject the whole
     * token; the caller decides whether to ignore it or reject the request.
     *
     * @param claim the raw role claim value, e.g. {@code "tenant-operator"}
     * @return the matching role, or empty if {@code claim} does not name one of the four roles
     */
    public static Optional<Role> fromClaim(String claim) {
        if (claim == null || claim.isBlank()) {
            return Optional.empty();
        }
        // Exact match against the four spec §10.3 names only (case-insensitive, trimmed) -- no
        // separator tolerance (e.g. "platform_admin"), so a near-miss spelling fails closed as
        // "no role" instead of being guessed at.
        String normalized = claim.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "platform-admin" -> Optional.of(PLATFORM_ADMIN);
            case "tenant-owner" -> Optional.of(TENANT_OWNER);
            case "tenant-operator" -> Optional.of(TENANT_OPERATOR);
            case "tenant-viewer" -> Optional.of(TENANT_VIEWER);
            default -> Optional.empty();
        };
    }
}
