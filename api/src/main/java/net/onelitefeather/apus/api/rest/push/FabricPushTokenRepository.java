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

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link PushTokenRepository} backed by Kubernetes {@link Secret}s. Deliberately not backed by
 * any {@code WorldSourceSpec}/{@code TenantSpec} CRD field -- neither carries a token field (this
 * task's scope is {@code ingest/.../connector/} and {@code api/}, not the CRD types under {@code
 * operator/}) -- so a token lives entirely as a plain, built-in Kubernetes resource instead,
 * exactly like the S3/Pterodactyl credentials {@code WorldSourceSpec.*.credentialsSecretRef}
 * already reference (design spec §10.1's "eigene S3-Credentials als Secret").
 *
 * <p><b>Expected shape</b> (contract for whatever creates these Secrets -- a platform-admin/
 * tenant-owner today, a future operator reconciler eventually):
 *
 * <ul>
 *   <li>lives in the tenant's own namespace ({@code bluemap-<tenant>}), like every other
 *       tenant-scoped resource (design spec §10.1);
 *   <li>labelled {@value #SERVICE_TOKEN_LABEL_KEY}: {@value #SERVICE_TOKEN_LABEL_VALUE} -- this
 *       is the only way this class finds it, since the namespace is exactly what a raw token does
 *       not carry;
 *   <li>{@code data.token} (or equivalently {@code stringData.token} at creation time) holds the
 *       raw shared-secret value the Paper plugin also holds.
 * </ul>
 *
 * <p><b>Why a cluster-wide list.</b> {@code POST /api/push/{token}} carries nothing but the
 * token -- no tenant name, no namespace, no JWT to read a claim from (see {@link
 * PushTokenRepository}'s Javadoc for why this endpoint is unlike every other one in this module).
 * The token itself is the only input, so resolving it to a namespace necessarily means searching
 * across namespaces; this is exactly the kind of cluster-wide, cross-tenant read design spec
 * §10.3 reserves for the backend's own ServiceAccount ("Das Backend ist der Durchsetzungspunkt").
 * The label scopes that search to service-token Secrets specifically, not every Secret in the
 * cluster -- but it still requires the deployment to grant this ServiceAccount cluster-wide
 * {@code get}/{@code list} on Secrets carrying that label. That RBAC grant is a deployment
 * concern outside this class (and outside this task's {@code ingest/.../connector/}+{@code api/}
 * scope) -- documented here so whoever wires it up has an exact requirement to satisfy.
 *
 * <p><b>Constant-time, exhaustive comparison.</b> {@link #resolveNamespace} runs {@link
 * MessageDigest#isEqual(byte[], byte[])} -- the JDK's documented constant-time byte comparison,
 * not {@code String.equals}/{@code Arrays.equals} which both short-circuit on the first
 * differing byte and would leak a correct token prefix through response timing one guess at a
 * time -- against *every* candidate Secret, never returning early once a match is found. Stopping
 * early would leak, through timing, how many service-token Secrets exist before the caller's own
 * tenant's -- a narrower signal than a whole token, but still cross-tenant information this
 * endpoint must not leak (task brief: "niemals einen Hinweis darauf, ob es den Mandanten gibt").
 */
@Singleton
public class FabricPushTokenRepository implements PushTokenRepository {

    /** See the class Javadoc's "Expected shape" for the full Secret contract this key is part of. */
    public static final String SERVICE_TOKEN_LABEL_KEY = "apus.onelitefeather.net/service-token";

    public static final String SERVICE_TOKEN_LABEL_VALUE = "world-push";

    /** The key under {@code Secret.data} (base64-encoded, as all Secret data is) holding the raw token. */
    public static final String TOKEN_DATA_KEY = "token";

    private final KubernetesClient client;

    public FabricPushTokenRepository(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public Optional<String> resolveNamespace(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        byte[] supplied = rawToken.getBytes(StandardCharsets.UTF_8);

        List<Secret> candidates = client.secrets()
                .inAnyNamespace()
                .withLabel(SERVICE_TOKEN_LABEL_KEY, SERVICE_TOKEN_LABEL_VALUE)
                .list()
                .getItems();

        // Scans every candidate and never returns on the first match -- see the class Javadoc's
        // "Constant-time, exhaustive comparison" for why an early return would itself be a
        // (narrower, but still real) timing side-channel.
        String matchedNamespace = null;
        for (Secret candidate : candidates) {
            byte[] stored = decodedToken(candidate);
            boolean matches = stored != null && MessageDigest.isEqual(stored, supplied);
            if (matches) {
                matchedNamespace = candidate.getMetadata().getNamespace();
            }
        }
        return Optional.ofNullable(matchedNamespace);
    }

    private static byte[] decodedToken(Secret secret) {
        Map<String, String> data = secret.getData();
        if (data == null) {
            return null;
        }
        String encoded = data.get(TOKEN_DATA_KEY);
        if (encoded == null) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            // A malformed Secret must not crash the whole lookup for every other tenant's token.
            return null;
        }
    }
}
