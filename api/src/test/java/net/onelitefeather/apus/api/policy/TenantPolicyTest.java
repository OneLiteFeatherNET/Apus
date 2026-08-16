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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TenantPolicyTest {

    private final TenantPolicy policy = new TenantPolicy();

    private static PolicyEntryView locked(String key, String type, String value) {
        return new PolicyEntryView(key, type, value, true);
    }

    private static PolicyEntryView unlocked(String key, String type, String value) {
        return new PolicyEntryView(key, type, value, false);
    }

    @Test
    void noPolicyAllowsEverything() {
        assertTrue(policy.rejectSourceType(List.of(), "s3").isEmpty());
        assertTrue(policy.rejectPoll(List.of(), "1s").isEmpty());
        assertTrue(policy.rejectKeepVersions(List.of(), 999).isEmpty());
        assertTrue(policy.rejectForceRender(List.of(), true).isEmpty());
    }

    @Test
    void aNullPolicyIsTreatedAsNoPolicy() {
        assertTrue(policy.rejectSourceType(null, "s3").isEmpty());
    }

    @Test
    void anUnlockedEntryNeverRefuses() {
        // This is the whole difference between "override" and "lock": an unlocked entry is the
        // platform's recommendation and the interfaces pre-fill with it. Enforcing it anyway
        // would make the lock switch meaningless.
        List<PolicyEntryView> advisory = List.of(unlocked("source.types.allowed", "stringList", "s3"));

        assertTrue(policy.rejectSourceType(advisory, "push").isEmpty());
    }

    @Test
    void aLockedSourceTypeListRefusesAnythingOutsideIt() {
        List<PolicyEntryView> only = List.of(locked("source.types.allowed", "stringList", "s3,push"));

        assertTrue(policy.rejectSourceType(only, "s3").isEmpty());
        assertTrue(policy.rejectSourceType(only, "push").isEmpty());

        Optional<String> refusal = policy.rejectSourceType(only, "pterodactyl");
        assertTrue(refusal.isPresent());
        // The message has to name the option, or an administrator cannot tell the tenant which
        // of their own rules refused it.
        assertTrue(refusal.get().contains("source.types.allowed"), refusal.get());
    }

    @Test
    void anEmptyLockedListRefusesEveryType() {
        List<PolicyEntryView> none = List.of(locked("source.types.allowed", "stringList", ""));

        assertTrue(policy.rejectSourceType(none, "s3").isPresent());
    }

    @Test
    void aLockedPollMinimumRefusesAnythingShorter() {
        List<PolicyEntryView> floor = List.of(locked("source.poll.minimum", "duration", "5m"));

        assertTrue(policy.rejectPoll(floor, "5m").isEmpty());
        assertTrue(policy.rejectPoll(floor, "10m").isEmpty());
        assertTrue(policy.rejectPoll(floor, "1h").isEmpty());
        assertTrue(policy.rejectPoll(floor, "30s").isPresent());
    }

    @Test
    void aSourceWithNoPollIsNotComparedAgainstAMinimum() {
        // poll is optional: a source without one is only ingested on request, which is slower
        // than any floor rather than faster. Refusing it would be backwards.
        List<PolicyEntryView> floor = List.of(locked("source.poll.minimum", "duration", "5m"));

        assertTrue(policy.rejectPoll(floor, null).isEmpty());
        assertTrue(policy.rejectPoll(floor, "").isEmpty());
    }

    @Test
    void aLockedKeepVersionsMaximumRefusesAnythingLarger() {
        List<PolicyEntryView> cap = List.of(locked("source.keepVersions.maximum", "integer", "3"));

        assertTrue(policy.rejectKeepVersions(cap, 3).isEmpty());
        assertTrue(policy.rejectKeepVersions(cap, 1).isEmpty());
        assertTrue(policy.rejectKeepVersions(cap, null).isEmpty());
        assertTrue(policy.rejectKeepVersions(cap, 4).isPresent());
    }

    @Test
    void aLockedForceRenderBanRefusesOnlyForcedRenders() {
        List<PolicyEntryView> banned = List.of(locked("render.force.allowed", "boolean", "false"));

        assertTrue(policy.rejectForceRender(banned, false).isEmpty());
        assertTrue(policy.rejectForceRender(banned, true).isPresent());

        List<PolicyEntryView> permitted = List.of(locked("render.force.allowed", "boolean", "true"));
        assertTrue(policy.rejectForceRender(permitted, true).isEmpty());
    }

    @Test
    void anEntryWhoseValueDoesNotMatchItsTypeIsIgnoredRatherThanCrashing() {
        // The write path validates this, but a Tenant can also be edited with kubectl. A
        // malformed entry must not take the API down or refuse every request -- it is treated as
        // absent, which is the same as unregulated.
        List<PolicyEntryView> broken = List.of(locked("source.poll.minimum", "duration", "later"));

        assertTrue(policy.rejectPoll(broken, "1s").isEmpty());
    }

    @Test
    void anEntryWithTheWrongTypeForItsKeyIsIgnored() {
        List<PolicyEntryView> mistyped = List.of(locked("source.poll.minimum", "integer", "300"));

        assertTrue(policy.rejectPoll(mistyped, "1s").isEmpty());
    }

    @Test
    void anUnknownKeyIsNeverEnforcedEvenWhenLocked() {
        List<PolicyEntryView> unknown = List.of(locked("source.poll.maximum", "duration", "5m"));

        assertTrue(policy.rejectPoll(unknown, "1h").isEmpty());
        assertFalse(PolicyKey.isEnforced("source.poll.maximum"));
    }
}
