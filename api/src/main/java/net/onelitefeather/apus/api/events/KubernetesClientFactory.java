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
package net.onelitefeather.apus.api.events;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;

/**
 * Produces the {@link KubernetesClient} bean this package's watches and (if the direct fallback
 * is active, see {@link LogSourceFactory}) pod-log reads run on. Picks up ambient in-cluster or
 * kubeconfig configuration the same way {@code io.javaoperatorsdk} does for {@code :operator} --
 * {@link KubernetesClientBuilder#build()} with no explicit config, since design spec §10.3 has
 * the backend authenticate to the Kubernetes API with its own ServiceAccount, never
 * impersonation.
 *
 * <p><b>Known duplication risk:</b> no shared, module-wide place to declare this bean exists yet
 * (that would live outside {@code events/}, out of this task's file scope -- see the task 3
 * report). Task 2's {@code rest/} controllers need a {@link KubernetesClient} too and, built in
 * an isolated worktree in parallel, may declare an equivalent factory of its own; if so, the
 * module will end up with two {@code @Singleton KubernetesClient} bean definitions after merging,
 * which is only a problem where both are injected into the *same* class without a qualifier
 * (Micronaut allows multiple beans of one type to coexist; ambiguity only surfaces at an
 * unqualified injection point). Hoisting one shared factory out of both packages once merged is
 * the right follow-up.
 */
@Factory
class KubernetesClientFactory {

    @Singleton
    KubernetesClient kubernetesClient() {
        return new KubernetesClientBuilder().build();
    }
}
