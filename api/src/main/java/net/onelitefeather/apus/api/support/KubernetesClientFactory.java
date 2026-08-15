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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single {@link KubernetesClient} bean for this module, shared by every repository under
 * both {@code rest/} and {@code events/}.
 *
 * <p><b>Phase 5a consolidation:</b> task 2 ({@code rest/}) and task 3 ({@code events/}) were
 * built in parallel worktrees against the same module, neither with a build file it was allowed
 * to touch to declare a shared factory through. Each landed its own client wiring to avoid an
 * ambiguous-bean collision at merge time: task 2 behind a {@code RestKubernetesClient} wrapper
 * bean (since an unqualified second {@code @Singleton KubernetesClient} factory would have made
 * every unqualified injection point ambiguous), task 3 as its own {@code events}-local {@code
 * @Factory}. Both said as much in their own Javadoc/report as the documented follow-up. This
 * class is that follow-up: the one place either package injects {@link KubernetesClient} from,
 * now that a single factory can live outside both.
 *
 * <p>Picks up ambient in-cluster or kubeconfig configuration the same way {@code
 * io.javaoperatorsdk} does for {@code :operator} -- {@link KubernetesClientBuilder#build()} with
 * no explicit config, since design spec §10.3 has the backend authenticate to the Kubernetes API
 * with its own ServiceAccount, never impersonation.
 */
@Factory
public class KubernetesClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesClientFactory.class);

    @Singleton
    public KubernetesClient kubernetesClient() {
        KubernetesClient client = new KubernetesClientBuilder().build();
        // The master URL only. The ServiceAccount token the client authenticates with is never
        // touched here and must never be logged.
        LOGGER.info("Kubernetes client configured for {}", client.getMasterUrl());
        return client;
    }
}
