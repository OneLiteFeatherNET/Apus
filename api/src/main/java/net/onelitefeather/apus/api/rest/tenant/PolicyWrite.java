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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.onelitefeather.apus.api.policy.PolicyKey;
import net.onelitefeather.apus.api.policy.PolicyType;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.operator.api.PolicyEntry;

/**
 * Turns a caller's policy list into custom-resource entries, refusing the shapes that would
 * otherwise be stored as nonsense.
 *
 * <p>An unknown <i>key</i> is deliberately not refused: recording an intended rule ahead of the
 * code that enforces it is the point of the generic design, and the API reports such an entry as
 * unenforced rather than pretending. An unknown <i>type</i> is refused, because nothing could
 * ever interpret it.
 *
 * <p>A known key declaring the wrong type is also refused, and that one is worth stating: such an
 * entry would be stored, reported as {@code enforced} by the registry, and then silently skipped
 * by {@code TenantPolicy} for the type mismatch. An option that looks enforced and is not is
 * exactly the failure this design set out to prevent.
 */
final class PolicyWrite {

    private PolicyWrite() {}

    static List<PolicyEntry> toEntries(List<PolicyEntryRequest> requested) {
        List<PolicyEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (PolicyEntryRequest request : requested) {
            if (request.key() == null || request.key().isBlank()) {
                throw new BadRequestException("policy key must not be blank");
            }
            if (!seen.add(request.key())) {
                throw new BadRequestException("duplicate policy key '" + request.key() + "'");
            }

            PolicyType type = PolicyType.fromWireName(request.type())
                    .orElseThrow(() -> new BadRequestException(
                            "unknown policy type '" + request.type() + "' for key '" + request.key() + "'"));

            if (!type.accepts(request.value())) {
                throw new BadRequestException("value '" + request.value() + "' is not a valid " + type.wireName()
                        + " for key '" + request.key() + "'");
            }

            PolicyKey known = PolicyKey.fromKey(request.key()).orElse(null);
            if (known != null && known.type() != type) {
                throw new BadRequestException("key '" + known.key() + "' must be of type "
                        + known.type().wireName() + ", not " + type.wireName());
            }

            PolicyEntry entry = new PolicyEntry();
            entry.setKey(request.key());
            entry.setType(type.wireName());
            entry.setValue(request.value());
            entry.setLocked(Boolean.TRUE.equals(request.locked()));
            entries.add(entry);
        }
        return entries;
    }
}
