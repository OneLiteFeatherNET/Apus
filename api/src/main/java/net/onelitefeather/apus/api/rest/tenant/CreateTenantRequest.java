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
package net.onelitefeather.apus.api.rest.tenant;

import io.micronaut.serde.annotation.Serdeable;
import java.util.List;

/**
 * Request body for {@code POST /api/tenants}. {@code name} becomes the {@code Tenant}'s
 * {@code metadata.name} and therefore, via {@code TenantReconciler}, the tenant slug used
 * throughout the platform -- validated by hand in {@code TenantController} since
 * {@code micronaut-validation} is not available (see {@code BadRequestException}'s Javadoc).
 */
@Serdeable
public record CreateTenantRequest(
        String name, String displayName, String storageQuota, Long maxObjects, List<String> allowedHostingDomains) {}
