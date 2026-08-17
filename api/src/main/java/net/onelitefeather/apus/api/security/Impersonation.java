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

import java.util.Objects;

/**
 * One request being served as somebody else.
 *
 * @param realSubject who actually authenticated -- kept for the audit trail, and never replaced.
 *     Every log line about an impersonated request names this, not the effective principal:
 *     "someone did X" is useless if the someone is the person they were pretending to be
 * @param effective who the request is authorised as. Always a narrowing of {@link #realSubject}'s
 *     own authority -- see {@link ImpersonationPolicy}
 */
public record Impersonation(String realSubject, ApusPrincipal effective) {

    public Impersonation {
        Objects.requireNonNull(realSubject, "realSubject must not be null");
        Objects.requireNonNull(effective, "effective must not be null");
    }

    /** A short, log-safe description: {@code root as tenant-owner of acme}. */
    public String describe() {
        return realSubject + " acting as '" + effective.subject() + "' in tenant '" + effective.tenant() + "'";
    }
}
