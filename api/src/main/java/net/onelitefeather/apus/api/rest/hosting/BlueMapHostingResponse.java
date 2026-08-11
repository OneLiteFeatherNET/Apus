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
package net.onelitefeather.apus.api.rest.hosting;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;
import net.onelitefeather.apus.api.rest.support.ConditionResponse;
import net.onelitefeather.apus.operator.api.BlueMapHosting;

/** A {@link BlueMapHosting}, as {@code GET /api/hostings} exposes it. */
@Serdeable
public record BlueMapHostingResponse(
        String name,
        List<String> maps,
        String hostname,
        String url,
        boolean ready,
        int replicas,
        List<ConditionResponse> conditions) {

    public static BlueMapHostingResponse from(BlueMapHosting hosting) {
        var spec = hosting.getSpec();
        var status = hosting.getStatus();
        List<String> maps =
                spec.getMaps().stream().map(ref -> ref == null ? null : ref.getName()).toList();
        return new BlueMapHostingResponse(
                hosting.getMetadata().getName(),
                maps,
                spec.getHostname(),
                status.getUrl(),
                status.isReady(),
                spec.getReplicas(),
                status.getConditions().stream().map(ConditionResponse::from).toList());
    }
}
