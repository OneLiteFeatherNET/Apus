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

import jakarta.inject.Singleton;
import java.util.List;
import java.util.Optional;

/**
 * Decides whether a value a tenant asked for is refused by that tenant's policy.
 *
 * <p>Pure: no Kubernetes client, no application context, nothing to mock. Every branch of the
 * registry is therefore covered by plain unit tests.
 *
 * <p><b>Three ways an entry is ignored</b>, all deliberate and all tested. An unlocked entry is a
 * recommendation rather than a rule — that distinction is the entire difference between
 * "override" and "lock". An entry whose key this module does not know cannot be enforced by
 * definition. And an entry whose value does not parse as its declared type — which the write path
 * rejects, but {@code kubectl edit} does not — is treated as absent rather than as a reason to
 * refuse everything: a malformed policy must not become an outage.
 *
 * <p>Policy narrows choices inside access the caller already has. It never widens access, and it
 * is never consulted to decide whether a caller may act on a tenant at all — that stays with
 * {@code TenantAccess} and the role checks.
 */
@Singleton
public class TenantPolicy {

    /** @return the message for the {@code 400}, or empty when the request is allowed. */
    public Optional<String> rejectSourceType(List<PolicyEntryView> policy, String requestedType) {
        return lockedValue(policy, PolicyKey.SOURCE_TYPES_ALLOWED).flatMap(raw -> {
            List<String> allowed = PolicyType.STRING_LIST.parseStringList(raw);
            if (allowed.contains(requestedType)) {
                return Optional.empty();
            }
            return Optional.of("source type '" + requestedType + "' is not allowed for this tenant ("
                    + PolicyKey.SOURCE_TYPES_ALLOWED.key() + " allows: "
                    + (allowed.isEmpty() ? "none" : String.join(", ", allowed)) + ")");
        });
    }

    /** @return the message for the {@code 400}, or empty when the request is allowed. */
    public Optional<String> rejectPoll(List<PolicyEntryView> policy, String requestedPoll) {
        // No poll at all means "only when asked", which is slower than any floor rather than
        // faster -- comparing it would refuse the most conservative choice available.
        if (requestedPoll == null || requestedPoll.isBlank()) {
            return Optional.empty();
        }
        return lockedValue(policy, PolicyKey.SOURCE_POLL_MINIMUM).flatMap(raw -> {
            if (!PolicyType.DURATION.accepts(requestedPoll)) {
                // Shape validation belongs to the controller; a policy has nothing to say about
                // a value it cannot interpret.
                return Optional.empty();
            }
            long minimum = PolicyType.DURATION.parseDurationSeconds(raw);
            long requested = PolicyType.DURATION.parseDurationSeconds(requestedPoll);
            if (requested >= minimum) {
                return Optional.empty();
            }
            return Optional.of("poll interval '" + requestedPoll + "' is shorter than this tenant's minimum of "
                    + raw + " (" + PolicyKey.SOURCE_POLL_MINIMUM.key() + ")");
        });
    }

    /** @return the message for the {@code 400}, or empty when the request is allowed. */
    public Optional<String> rejectKeepVersions(List<PolicyEntryView> policy, Integer requested) {
        if (requested == null) {
            return Optional.empty();
        }
        return lockedValue(policy, PolicyKey.SOURCE_KEEP_VERSIONS_MAXIMUM).flatMap(raw -> {
            long maximum = PolicyType.INTEGER.parseInteger(raw);
            if (requested <= maximum) {
                return Optional.empty();
            }
            return Optional.of("keepVersions " + requested + " exceeds this tenant's maximum of " + maximum
                    + " (" + PolicyKey.SOURCE_KEEP_VERSIONS_MAXIMUM.key() + ")");
        });
    }

    /** @return the message for the {@code 400}, or empty when the request is allowed. */
    public Optional<String> rejectForceRender(List<PolicyEntryView> policy, boolean force) {
        if (!force) {
            return Optional.empty();
        }
        return lockedValue(policy, PolicyKey.RENDER_FORCE_ALLOWED).flatMap(raw -> {
            if (PolicyType.BOOLEAN.parseBoolean(raw)) {
                return Optional.empty();
            }
            return Optional.of("forced renders are not allowed for this tenant ("
                    + PolicyKey.RENDER_FORCE_ALLOWED.key() + ")");
        });
    }

    /**
     * The value of a locked entry for {@code key} — but only if it declares the type the key
     * expects and that value parses. Anything else is absent; see the class Javadoc for why each
     * of those is deliberate rather than lenient.
     */
    private Optional<String> lockedValue(List<PolicyEntryView> policy, PolicyKey key) {
        if (policy == null) {
            return Optional.empty();
        }
        return policy.stream()
                .filter(entry -> key.key().equals(entry.key()))
                .filter(PolicyEntryView::locked)
                .filter(entry -> key.type().wireName().equals(entry.type()))
                .filter(entry -> key.type().accepts(entry.value()))
                .map(PolicyEntryView::value)
                .findFirst();
    }
}
