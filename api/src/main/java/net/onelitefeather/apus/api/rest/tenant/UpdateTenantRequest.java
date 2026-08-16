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
 * Request body for {@code PATCH /api/tenants/{name}} -- the only way to change quota or allowed
 * hosting domains on a tenant after creation (design spec §10.3: {@code platform-admin} may
 * "create/modify/delete tenants, quotas"). {@code name} is deliberately not repeated here, nor
 * is it ever taken from anywhere but the path -- see {@code TenantController#update}.
 *
 * <p>Partial-update semantics, same as {@link CreateTenantRequest}: a {@code null} field leaves
 * the current value untouched rather than clearing it, so a caller changing only the storage
 * quota does not have to first re-read and resend the current allowed domains. There is
 * deliberately no way to change {@code displayName} here -- out of this endpoint's stated scope
 * (design spec §10.3: quota and domains only).
 *
 * <p>{@code policy} follows the same rule at the field level -- omitted leaves the current entries
 * untouched -- but a <b>present list replaces all of them</b>. Entry-level patching is
 * deliberately not offered: with a free-form key space a merge would need a delete sentinel, and
 * "send the list you want to hold" is both easier to reason about and easier to render a form for.
 */
@Serdeable
public record UpdateTenantRequest(
        String storageQuota, Long maxObjects, List<String> allowedHostingDomains, List<PolicyEntryRequest> policy) {}
