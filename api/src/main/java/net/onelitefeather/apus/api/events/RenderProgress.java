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

import io.micronaut.serde.annotation.Serdeable;
import net.onelitefeather.apus.operator.api.BlueMapRender;

/**
 * SSE payload for {@code GET /api/renders/{id}/events} -- an independent response type, not the
 * {@link net.onelitefeather.apus.operator.api.BlueMapRenderStatus} custom resource field it is
 * built from, for the same reason task 2's response models are independent types: a CR status
 * field is the operator's business, not a public contract that should change every time the CRD
 * does.
 *
 * @param phase raw {@code status.phase} (design spec §8.5); {@code null} until the operator sets it
 * @param percent 0-100, how far the current render job has gotten
 * @param currentMap the map/dimension currently being rendered, or {@code null}
 * @param etaSeconds estimated remaining seconds, meaningless (any value, including negative) when {@code degraded}
 * @param degraded {@code true} when the runner could not determine real progress (design spec §7.2)
 */
@Serdeable
record RenderProgress(String phase, double percent, String currentMap, long etaSeconds, boolean degraded) {

    static RenderProgress from(BlueMapRender render) {
        var status = render.getStatus();
        var progress = status.getProgress();
        return new RenderProgress(
                status.getPhase(), progress.getPercent(), progress.getCurrentMap(), progress.getEtaSeconds(),
                progress.isDegraded());
    }
}
