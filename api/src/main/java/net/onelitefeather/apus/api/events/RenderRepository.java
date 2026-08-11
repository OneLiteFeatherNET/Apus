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

import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import java.util.Optional;
import net.onelitefeather.apus.operator.api.BlueMapRender;

/**
 * Read access to {@link BlueMapRender} for the event streams, kept behind an interface so
 * {@link RenderStreamController} can be unit-tested with a hand-written fake instead of a mocking
 * framework or a real cluster -- neither {@code kubernetes-server-mock} nor a mocking library is
 * a test dependency of the {@code api} module (see the task 3 report).
 */
interface RenderRepository {

    /**
     * A single, point-in-time read -- used for the tenant/existence check that must happen
     * before any stream opens, and to seed a watch's starting {@code resourceVersion} so no
     * update landing between this read and the watch registration is missed.
     */
    Optional<BlueMapRender> find(String namespace, String name);

    /**
     * Watches one {@link BlueMapRender} from a known {@code resourceVersion} onward. The caller
     * owns the returned {@link Watch} and must close it once the stream ends (SseSource's
     * {@code Wiring} contract does this automatically via the cleanup action).
     */
    Watch watch(String namespace, String name, String resourceVersion, Watcher<BlueMapRender> watcher);
}
