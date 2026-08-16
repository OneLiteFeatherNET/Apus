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
import net.onelitefeather.apus.operator.api.PolicyEntry;

/**
 * One policy entry as the API returns it.
 *
 * <p>{@code enforced} is stored nowhere — it is computed from {@link PolicyKey}'s registry on
 * every read, because whether a key bites is a property of this module's code and changes when
 * the code does. Returning it is what lets the console mark an entry that will do nothing,
 * instead of drawing a lock switch that quietly locks nothing.
 */
@Serdeable
public record PolicyEntryResponse(String key, String type, String value, boolean locked, boolean enforced) {

    public static PolicyEntryResponse from(PolicyEntry entry) {
        return new PolicyEntryResponse(
                entry.getKey(),
                entry.getType(),
                entry.getValue(),
                entry.isLocked(),
                PolicyKey.isEnforced(entry.getKey()));
    }
}
