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

import java.util.Set;
import net.onelitefeather.apus.operator.api.BlueMapRenderStatus;

/**
 * Which {@link BlueMapRenderStatus#getPhase()} values are terminal (design spec §8.5:
 * {@code Pending|Syncing|Rendering|Finalizing|Succeeded|Failed}).
 *
 * <p>{@code BlueMapRenderStatus.phase} is a plain {@code String}, not an enum -- mirrored here
 * as the same two literal values {@code BlueMapRenderReconciler} in {@code :operator} treats as
 * terminal, rather than importing that class's private constants (it has none it exposes). Event
 * streams must stop once a render reaches one of these: otherwise every open browser tab holding
 * an SSE connection open keeps its underlying Kubernetes watch alive forever (see the task 3
 * report's "operational point" section).
 */
final class RenderPhases {

    private static final Set<String> TERMINAL = Set.of("Succeeded", "Failed");

    private RenderPhases() {}

    /** @param phase the raw {@code status.phase} value; {@code null} (not yet set) is not terminal */
    static boolean isTerminal(String phase) {
        return phase != null && TERMINAL.contains(phase);
    }
}
