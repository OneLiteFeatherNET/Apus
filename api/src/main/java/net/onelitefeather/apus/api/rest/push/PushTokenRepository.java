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
package net.onelitefeather.apus.api.rest.push;

import java.util.Optional;

/**
 * Resolves a raw {@code world:push} service token (design spec §10.3) to the single namespace it
 * authorizes -- the only lookup {@code PushController} is allowed to make before it knows which
 * tenant a {@code POST /api/push/{token}} call belongs to.
 *
 * <p><b>This is deliberately not a JWT.</b> Every other authenticated endpoint in this module
 * validates a JWT issued by the identity broker (design spec §10.3, {@code
 * PrincipalResolver}/{@code TenantResolver}). A push token is different on purpose: it is a
 * long-lived, tenant-bound bearer secret a Paper server plugin holds, deliberately not tied to
 * any user login (§10.3: "otherwise a person leaving would cripple the server upload"). It
 * arrives as a path segment, not a bearer JWT, and {@link #resolveNamespace} must
 * compare it against every known token in constant time (see {@code
 * FabricPushTokenRepository#resolveNamespace} for how) so that no amount of failed guesses lets
 * an attacker learn a correct token one character at a time, and a non-matching token must look
 * identical -- in both response and timing -- whether or not the tenant it might have belonged to
 * even exists.
 *
 * <p>An interface so controller tests can supply an in-memory fake; see {@code
 * net.onelitefeather.apus.api.rest.tenant.TenantRepository}'s Javadoc for why.
 */
public interface PushTokenRepository {

    /**
     * @param rawToken the token exactly as it arrived in the {@code POST /api/push/{token}} path
     *     segment; {@code null} or blank is a valid input and always resolves to {@link
     *     Optional#empty()}
     * @return the namespace this token authorizes push access to, or {@link Optional#empty()} if
     *     no known token matches -- never distinguishes "no such token" from "token valid for a
     *     different tenant" in what it returns
     */
    Optional<String> resolveNamespace(String rawToken);
}
