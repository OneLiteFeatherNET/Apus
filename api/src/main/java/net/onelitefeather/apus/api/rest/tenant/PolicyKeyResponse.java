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
import net.onelitefeather.apus.api.policy.PolicyKey;

/**
 * One entry of the enforceable-key catalogue, so a console form cannot drift from what the API
 * actually enforces.
 */
@Serdeable
public record PolicyKeyResponse(String key, String type, String description) {

    public static PolicyKeyResponse from(PolicyKey key) {
        return new PolicyKeyResponse(key.key(), key.type().wireName(), key.description());
    }
}
