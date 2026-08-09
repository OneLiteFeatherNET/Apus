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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Proves the real fabric8 wiring {@code PushControllerTest}'s in-memory fake cannot: the
 * cluster-wide, label-scoped Secret query and the base64 {@code data.token} decoding.
 */
@EnableKubernetesMockClient(crud = true)
class FabricPushTokenRepositoryTest {

    KubernetesClient client;

    private FabricPushTokenRepository repository() {
        return new FabricPushTokenRepository(client);
    }

    private void serviceTokenSecret(String namespace, String name, String rawToken) {
        Secret secret = new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .withLabels(Map.of(
                                FabricPushTokenRepository.SERVICE_TOKEN_LABEL_KEY,
                                FabricPushTokenRepository.SERVICE_TOKEN_LABEL_VALUE))
                        .build())
                .withData(Map.of(
                        FabricPushTokenRepository.TOKEN_DATA_KEY,
                        Base64.getEncoder().encodeToString(rawToken.getBytes(StandardCharsets.UTF_8))))
                .build();
        client.secrets().inNamespace(namespace).resource(secret).create();
    }

    @Test
    void resolvesTheNamespaceOfTheMatchingSecret() {
        serviceTokenSecret("bluemap-acme", "apus-push-token", "acme-super-secret-token");

        var result = repository().resolveNamespace("acme-super-secret-token");

        assertTrue(result.isPresent());
        assertEquals("bluemap-acme", result.get());
    }

    @Test
    void aTokenThatMatchesNoSecretResolvesToEmpty() {
        serviceTokenSecret("bluemap-acme", "apus-push-token", "acme-super-secret-token");

        assertTrue(repository().resolveNamespace("some-other-token").isEmpty());
    }

    @Test
    void blankOrNullTokenResolvesToEmptyWithoutQueryingTheCluster() {
        assertTrue(repository().resolveNamespace("").isEmpty());
        assertTrue(repository().resolveNamespace(null).isEmpty());
    }

    @Test
    void picksTheCorrectNamespaceAmongMultipleTenantsTokens() {
        serviceTokenSecret("bluemap-acme", "apus-push-token", "acme-token");
        serviceTokenSecret("bluemap-globex", "apus-push-token", "globex-token");
        serviceTokenSecret("bluemap-initech", "apus-push-token", "initech-token");

        assertEquals("bluemap-globex", repository().resolveNamespace("globex-token").orElseThrow());
        assertEquals("bluemap-acme", repository().resolveNamespace("acme-token").orElseThrow());
    }

    @Test
    void aSecretWithoutTheServiceTokenLabelIsIgnored() {
        // A Secret that merely happens to have a "token" data key, but isn't labelled as a
        // service token (e.g. an S3/Pterodactyl credentials Secret) must never be treated as one.
        Secret unlabelled = new SecretBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withName("s3-creds")
                        .withNamespace("bluemap-acme")
                        .build())
                .withData(Map.of(
                        FabricPushTokenRepository.TOKEN_DATA_KEY,
                        Base64.getEncoder().encodeToString("not-a-push-token".getBytes(StandardCharsets.UTF_8))))
                .build();
        client.secrets().inNamespace("bluemap-acme").resource(unlabelled).create();

        assertTrue(repository().resolveNamespace("not-a-push-token").isEmpty());
    }
}
