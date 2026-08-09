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
package net.onelitefeather.apus.api.support;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;

/**
 * Replaces {@link KubernetesClientFactory}'s {@link KubernetesClient} bean with one pointed at
 * the k3s cluster {@code TenantIsolationIntegrationTest} starts via Testcontainers, active only
 * under the {@code k3s} Micronaut environment that test declares. Every {@code Fabric8*Repository}
 * in this module stays completely unaware of this -- they inject {@link KubernetesClient}, not
 * this factory -- so the integration test proves cross-tenant isolation through the real,
 * production repository implementations against a real API server, not fakes.
 *
 * <p>{@code apus.test.k3s.kubeconfig} is supplied by {@code
 * TenantIsolationIntegrationTest#getProperties()} ({@link
 * io.micronaut.test.support.TestPropertyProvider}), which starts the container and applies the
 * generated CRDs to it before this factory (or anything else in the application context) is
 * built.
 */
@Factory
@Requires(env = "k3s")
class K3sTestKubernetesClientFactory {

    @Singleton
    @Replaces(bean = KubernetesClient.class, factory = KubernetesClientFactory.class)
    KubernetesClient kubernetesClient(@Value("${apus.test.k3s.kubeconfig}") String kubeconfigYaml) {
        Config config = Config.fromKubeconfig(kubeconfigYaml);
        return new KubernetesClientBuilder().withConfig(config).build();
    }
}
