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
package net.onelitefeather.apus.api.rest.render;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import net.onelitefeather.apus.api.rest.support.ConditionResponse;
import net.onelitefeather.apus.operator.api.BlueMapRender;

/**
 * A {@link BlueMapRender}, as {@code /api/renders} and {@code POST /api/maps/{id}/render}
 * expose it. Omits {@code jobName} and {@code bundleUrl}/{@code bundleVersion} -- Kubernetes Job
 * names and internal bundle addressing are the operator's bookkeeping, not something a caller
 * driving renders through this API needs to see (design plan: response models are their own
 * types, not managed CR fields passed through).
 */
@Serdeable
public record BlueMapRenderResponse(
        String name,
        String mapRef,
        boolean force,
        String phase,
        ProgressResponse progress,
        String startTime,
        String completionTime,
        List<ConditionResponse> conditions) {

    public static BlueMapRenderResponse from(BlueMapRender render) {
        var spec = render.getSpec();
        var status = render.getStatus();
        var progress = status.getProgress();
        return new BlueMapRenderResponse(
                render.getMetadata().getName(),
                spec.getMapRef() == null ? null : spec.getMapRef().getName(),
                spec.isForce(),
                status.getPhase(),
                new ProgressResponse(
                        progress.getPercent(), progress.getCurrentMap(), progress.getEtaSeconds(), progress.isDegraded()),
                status.getStartTime(),
                status.getCompletionTime(),
                status.getConditions().stream().map(ConditionResponse::from).toList());
    }

    @Serdeable
    public record ProgressResponse(double percent, String currentMap, long etaSeconds, boolean degraded) {}
}
