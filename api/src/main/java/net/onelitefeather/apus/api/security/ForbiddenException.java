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
package net.onelitefeather.apus.api.security;

/**
 * Thrown when a caller is authenticated but not authorized for what they asked to do -- most
 * centrally, by {@link TenantResolver} when a principal has no tenant to resolve a namespace
 * for. Deliberately unchecked: every call site up to the eventual HTTP boundary treats this the
 * same way, so forcing it into every intermediate method signature would add noise without
 * adding safety.
 *
 * <p>Mapping this to an HTTP status (403, or 404 where revealing "forbidden" would itself leak
 * that a foreign tenant's resource exists -- see design plan §"Fehler geben keine Auskunft") is
 * the responsibility of the REST layer that consumes this module, not of this exception itself.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
