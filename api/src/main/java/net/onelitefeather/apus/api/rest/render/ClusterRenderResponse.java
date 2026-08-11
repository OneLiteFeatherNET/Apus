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
import net.onelitefeather.apus.operator.api.BlueMapRender;

/**
 * One render, as {@code GET /api/renders/cluster} exposes it -- the {@code platform-admin}-only
 * cluster-wide view (design spec §10.3: "clusterweite Sicht"). Wraps the ordinary {@link
 * BlueMapRenderResponse} rather than duplicating its fields, and adds exactly the one thing a
 * single tenant's own {@code GET /api/renders} does not need to say about itself: which tenant
 * this render belongs to. {@code tenant} is the {@code Tenant} custom resource's own {@code
 * metadata.name} -- resolved by {@link BlueMapRenderController#listCluster} from {@code
 * TenantRepository}, never guessed back out of a namespace string (that reverse mapping belongs
 * to no one; see {@code TenantResolver}'s Javadoc on why it has exactly one public method).
 */
@Serdeable
public record ClusterRenderResponse(String tenant, BlueMapRenderResponse render) {

    public static ClusterRenderResponse from(String tenant, BlueMapRender render) {
        return new ClusterRenderResponse(tenant, BlueMapRenderResponse.from(render));
    }
}
