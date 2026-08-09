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
package net.onelitefeather.apus.api.rest.support;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.inject.Singleton;

/**
 * The single fabric8 {@link KubernetesClient} shared by every repository under {@code rest/}.
 *
 * <p>Deliberately not exposed as a bean of type {@link KubernetesClient} itself. Task 3 (SSE
 * progress/log streaming under {@code events/}) is developed in a parallel worktree against the
 * same module and will need its own client wiring for watches; if both tasks registered an
 * unqualified {@code @Singleton KubernetesClient}, every injection point requesting that type
 * anywhere in the module -- including ones neither task added -- would become ambiguous the
 * moment both land, which Micronaut only reports at context-build time, not at compile time.
 * Neither task owns a build file to coordinate a shared factory through (see task-2-brief.md's
 * file restriction), so the simplest conflict-proof option is for each task's client wiring to
 * live behind its own bean type. This class is {@code rest/}'s.
 */
@Singleton
public class RestKubernetesClient implements AutoCloseable {

    private final KubernetesClient delegate;

    public RestKubernetesClient() {
        this.delegate = new KubernetesClientBuilder().build();
    }

    /** The underlying client. Never held onto beyond the caller's own method scope. */
    public KubernetesClient get() {
        return delegate;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
