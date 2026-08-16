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
package net.onelitefeather.apus.api.policy;

import java.util.Arrays;
import java.util.Optional;

/**
 * The policy keys this module knows how to enforce.
 *
 * <p>Four of them, and they are exactly the four choices a tenant can make today: which source
 * type to create, how often it is polled, how many snapshots are kept, and whether a render may
 * be forced. Every other key a tenant's policy carries is stored, returned and displayed, and
 * changes nothing — {@link #isEnforced(String)} is what lets the interfaces say so out loud
 * rather than drawing a lock switch that quietly locks nothing (design doc 2026-08-16, §4).
 *
 * <p>Adding a key here is a code change by construction: enforcement lives in the controller that
 * accepts the value, and no amount of generality in the storage removes that. The generic bag
 * buys the ability to record intent ahead of enforcement, not enforcement itself.
 */
public enum PolicyKey {
    SOURCE_TYPES_ALLOWED(
            "source.types.allowed",
            PolicyType.STRING_LIST,
            "Which source types a tenant may create. A type outside this list is refused."),
    SOURCE_POLL_MINIMUM(
            "source.poll.minimum",
            PolicyType.DURATION,
            "The shortest polling interval a tenant may set on a source."),
    SOURCE_KEEP_VERSIONS_MAXIMUM(
            "source.keepVersions.maximum",
            PolicyType.INTEGER,
            "The most snapshots a tenant may keep per source."),
    RENDER_FORCE_ALLOWED(
            "render.force.allowed",
            PolicyType.BOOLEAN,
            "Whether a tenant may force a full re-render, discarding existing tiles.");

    private final String key;
    private final PolicyType type;
    private final String description;

    PolicyKey(String key, PolicyType type, String description) {
        this.key = key;
        this.type = type;
        this.description = description;
    }

    public String key() {
        return key;
    }

    public PolicyType type() {
        return type;
    }

    /** One sentence, shown by the console beside the input. */
    public String description() {
        return description;
    }

    public static Optional<PolicyKey> fromKey(String key) {
        if (key == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(candidate -> candidate.key.equals(key)).findFirst();
    }

    public static boolean isEnforced(String key) {
        return fromKey(key).isPresent();
    }
}
