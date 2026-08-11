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

/**
 * Thrown when a resource does not exist in the caller's own namespace -- including, critically,
 * when it exists but only in a different tenant's namespace. Every repository in {@code rest/}
 * looks resources up already scoped to the caller's namespace (via {@code TenantResolver}), so a
 * foreign tenant's resource is indistinguishable from one that does not exist anywhere: both
 * produce this exception, and both therefore map to the same HTTP 404. That is deliberate -- see
 * task-2-brief.md: a 403 here would itself disclose that the resource exists under a different
 * tenant, turning the API into a directory of other tenants' resources.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
