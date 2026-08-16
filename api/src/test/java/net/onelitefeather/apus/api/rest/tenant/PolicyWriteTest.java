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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.operator.api.PolicyEntry;
import org.junit.jupiter.api.Test;

/**
 * The four ways a policy write is refused, and the two shapes that are deliberately accepted.
 *
 * <p>An unknown <i>key</i> is not among the refusals: storing one is the point of the generic
 * design. An unknown <i>type</i> is, because nothing could ever interpret it.
 */
class PolicyWriteTest {

    private static PolicyEntryRequest locked(String key, String type, String value) {
        return new PolicyEntryRequest(key, type, value, true);
    }

    @Test
    void aBlankKeyIsRefused() {
        BadRequestException thrown = assertThrows(
                BadRequestException.class, () -> PolicyWrite.toEntries(List.of(locked("", "string", "x"))));

        assertTrue(thrown.getMessage().contains("key"), thrown.getMessage());
    }

    @Test
    void aNullKeyIsRefused() {
        assertThrows(
                BadRequestException.class,
                () -> PolicyWrite.toEntries(List.of(new PolicyEntryRequest(null, "string", "x", false))));
    }

    @Test
    void anUnknownTypeIsRefused() {
        BadRequestException thrown = assertThrows(
                BadRequestException.class, () -> PolicyWrite.toEntries(List.of(locked("a.b", "timespan", "5m"))));

        assertTrue(thrown.getMessage().contains("timespan"), thrown.getMessage());
    }

    @Test
    void aValueThatDoesNotParseAsItsTypeIsRefused() {
        BadRequestException thrown = assertThrows(
                BadRequestException.class, () -> PolicyWrite.toEntries(List.of(locked("a.b", "duration", "soon"))));

        assertTrue(thrown.getMessage().contains("soon"), thrown.getMessage());
    }

    @Test
    void aDuplicateKeyIsRefusedRatherThanLastOneWinning() {
        BadRequestException thrown = assertThrows(
                BadRequestException.class,
                () -> PolicyWrite.toEntries(List.of(locked("a.b", "string", "1"), locked("a.b", "string", "2"))));

        assertTrue(thrown.getMessage().contains("a.b"), thrown.getMessage());
    }

    @Test
    void aKnownKeyMustDeclareTheTypeTheRegistryExpects() {
        // Otherwise the entry is stored, reported as enforced, and then silently skipped by
        // TenantPolicy for the type mismatch -- an option that looks enforced and is not is
        // exactly the failure this design set out to prevent.
        BadRequestException thrown = assertThrows(
                BadRequestException.class,
                () -> PolicyWrite.toEntries(List.of(locked("source.poll.minimum", "integer", "300"))));

        assertTrue(thrown.getMessage().contains("source.poll.minimum"), thrown.getMessage());
    }

    @Test
    void anUnknownKeyIsStored() {
        List<PolicyEntry> entries =
                PolicyWrite.toEntries(List.of(locked("render.concurrency.maximum", "integer", "2")));

        assertEquals(1, entries.size());
        assertEquals("render.concurrency.maximum", entries.get(0).getKey());
        assertEquals("integer", entries.get(0).getType());
    }

    @Test
    void anOmittedLockDefaultsToUnlocked() {
        List<PolicyEntry> entries =
                PolicyWrite.toEntries(List.of(new PolicyEntryRequest("a.b", "string", "x", null)));

        assertFalse(entries.get(0).isLocked());
    }

    @Test
    void anEmptyListClearsThePolicy() {
        assertTrue(PolicyWrite.toEntries(List.of()).isEmpty());
    }
}
