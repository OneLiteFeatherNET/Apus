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
package net.onelitefeather.apus.operator.tenant;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * The Kubernetes {@code Secret} shape a tenant's {@code world:push} service token is stored in
 * -- shared between the two independent places that need to agree on it: {@link
 * TenantReconciler} (which creates the Secret) and {@code
 * net.onelitefeather.apus.api.rest.push.FabricPushTokenRepository} in the {@code api} module
 * (which reads it to authenticate {@code POST /api/push/{token}}). {@code api} already depends
 * on {@code operator} for its CRD types (see {@code api/build.gradle.kts}), so these constants
 * live here as the one canonical definition rather than being duplicated (and risking drift, the
 * exact failure this phase's task brief calls out between {@code paper-worldpush} and {@code
 * api}) on both sides.
 *
 * <p><b>Design decision: tenant-scoped, not {@code WorldSource}-scoped.</b> The design spec
 * (§10.3) already settles this: "Service-Tokens sind mandantengebunden" (service tokens are
 * tenant-bound) -- deliberately not tied to any individual user login or, by extension, any one
 * {@code WorldSource}, so that renaming/recreating a push-type source (or a tenant running
 * several of them) never invalidates the one token {@code paper-worldpush} was configured with.
 * One Secret per tenant namespace is therefore enough; every {@code push}-type {@code
 * WorldSource} in that tenant shares it, exactly like {@link
 * net.onelitefeather.apus.api.rest.push.FabricPushTokenRepository#resolveNamespace} already
 * assumes (it resolves a token to a *namespace*, not to one specific source).
 *
 * <p><b>Never logged, never in status.</b> {@link #generate()} returns the raw token exactly
 * once, to the caller that is about to write it into {@code Secret.stringData} and nowhere else
 * -- {@link TenantReconciler} does not log it, and {@code TenantStatus} only ever records that
 * the Secret exists (by name; the name is a fixed, non-secret constant), never its value.
 */
public final class PushTokenSecrets {

    /** Label key marking a Secret as a {@code world:push} service token; the only way it is found. */
    public static final String LABEL_KEY = "apus.onelitefeather.net/service-token";

    /** Label value for {@link #LABEL_KEY} -- see {@link #LABEL_KEY}. */
    public static final String LABEL_VALUE = "world-push";

    /** The key under {@code Secret.data}/{@code Secret.stringData} holding the raw token. */
    public static final String TOKEN_KEY = "token";

    /**
     * Fixed name every tenant's push-token Secret is created/looked up under, within its own
     * namespace ({@code bluemap-<tenant>}). Fixed (not derived per-{@code WorldSource}) because
     * exactly one token exists per tenant -- see the class Javadoc -- and because a fixed name
     * is what lets the narrowest working RBAC grant restrict {@code get} to {@code
     * resourceNames: ["apus-push-token"]} instead of every Secret in the namespace; see {@code
     * FabricPushTokenRepository}'s Javadoc for the full RBAC discussion.
     */
    public static final String SECRET_NAME = "apus-push-token";

    /** 256 bits -- generous for a shared secret that is never brute-forced online (rate-limited by the API). */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PushTokenSecrets() {}

    /**
     * Generates a new cryptographically random token, URL-safe and unpadded so it can be used
     * verbatim as a URL path segment ({@code POST /api/push/{token}}, exactly how {@code
     * HttpPushNotifier} in {@code paper-worldpush} sends it) without any escaping.
     */
    public static String generate() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
