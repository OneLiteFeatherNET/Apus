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
package net.onelitefeather.apus.operator.ingest;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a single decoded value out of a {@code Secret}, for the one case in this module that
 * needs the actual credential value in-process rather than only a {@code secretKeyRef} (see
 * {@code RenderJobBuilder.fromSecret}): {@code WorldSourceReconciler} calling {@code
 * WorldSourceConnector.discover()} directly, and {@code WorldIngestReconciler}'s {@code
 * AwsBundleStore} pruning retained bundle versions. Both need a real S3/HTTP client built
 * in-process; a {@code Job}'s {@code secretKeyRef} only works for a container's own environment.
 *
 * <p><b>Never logged, never written to status.</b> Callers must keep the returned value
 * local -- it is a plaintext credential.
 */
public final class Secrets {

    private static final Logger LOGGER = LoggerFactory.getLogger(Secrets.class);

    private Secrets() {}

    /**
     * Returns the decoded value of {@code key} in the {@code Secret} named {@code secretName}
     * in {@code namespace}, or {@code null} if the secret, or the key within it, does not
     * exist.
     */
    public static String value(KubernetesClient client, String namespace, String secretName, String key) {
        if (secretName == null || secretName.isBlank()) {
            return null;
        }
        Secret secret = client.secrets().inNamespace(namespace).withName(secretName).get();
        if (secret == null || secret.getData() == null) {
            // Names only. A missing credentials Secret is a configuration problem someone has to
            // see -- silently handing a connector a null credential just produces a confusing
            // failure one layer down.
            LOGGER.warn("secret '{}' in namespace '{}' does not exist or carries no data", secretName, namespace);
            return null;
        }
        String encoded = secret.getData().get(key);
        if (encoded == null) {
            LOGGER.warn("secret '{}' in namespace '{}' has no key '{}'", secretName, namespace, key);
            return null;
        }
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }
}
