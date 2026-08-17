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
package net.onelitefeather.apus.api.directory;

/**
 * The identity provider could not be reached, or refused to answer.
 *
 * <p>Its own type rather than a generic failure because callers treat it differently on purpose:
 * a tenant whose storage and renders are perfectly fine must not become unreadable because
 * Microsoft is throttling. Panels that depend on the directory report themselves unavailable; the
 * page around them keeps working.
 *
 * <p>Never confused with "no such user" or "not permitted" -- those are a {@code null} return and
 * a {@link net.onelitefeather.apus.api.security.ForbiddenException} respectively. Collapsing them
 * would let an outage read as an empty directory, and an empty directory is something an
 * administrator would act on.
 */
public class DirectoryUnavailableException extends RuntimeException {

    public DirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public DirectoryUnavailableException(String message) {
        super(message);
    }
}
