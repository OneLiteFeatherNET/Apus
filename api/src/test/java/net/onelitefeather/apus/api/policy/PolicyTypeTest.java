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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyTypeTest {

    @Test
    void wireNamesAreStableBecauseTheyAreStoredInCustomResources() {
        // These strings live in Tenant manifests in Git. Renaming one silently reinterprets
        // every entry already written with it.
        assertEquals("string", PolicyType.STRING.wireName());
        assertEquals("integer", PolicyType.INTEGER.wireName());
        assertEquals("boolean", PolicyType.BOOLEAN.wireName());
        assertEquals("duration", PolicyType.DURATION.wireName());
        assertEquals("stringList", PolicyType.STRING_LIST.wireName());
    }

    @Test
    void anUnknownTypeNameIsEmptyRatherThanAGuess() {
        assertEquals(Optional.of(PolicyType.DURATION), PolicyType.fromWireName("duration"));
        assertEquals(Optional.empty(), PolicyType.fromWireName("timespan"));
        assertEquals(Optional.empty(), PolicyType.fromWireName(null));
        assertEquals(Optional.empty(), PolicyType.fromWireName(""));
    }

    @Test
    void integersAcceptWholeNumbersOnly() {
        assertTrue(PolicyType.INTEGER.accepts("3"));
        assertTrue(PolicyType.INTEGER.accepts("-1"));
        assertFalse(PolicyType.INTEGER.accepts("3.5"));
        assertFalse(PolicyType.INTEGER.accepts("three"));
        assertFalse(PolicyType.INTEGER.accepts(""));
        assertEquals(3L, PolicyType.INTEGER.parseInteger("3"));
    }

    @Test
    void booleansAcceptOnlyTrueAndFalse() {
        // Not Boolean.parseBoolean, which answers false for "yes", "1" and "maybe" alike -- an
        // administrator typing "yes" would silently get the opposite of what they meant.
        assertTrue(PolicyType.BOOLEAN.accepts("true"));
        assertTrue(PolicyType.BOOLEAN.accepts("false"));
        assertFalse(PolicyType.BOOLEAN.accepts("yes"));
        assertFalse(PolicyType.BOOLEAN.accepts("1"));
        assertTrue(PolicyType.BOOLEAN.parseBoolean("true"));
        assertFalse(PolicyType.BOOLEAN.parseBoolean("false"));
    }

    @Test
    void durationsUseTheSameSpellingTheRestOfApusUses() {
        // WorldSourceSpec.poll is a Go-style duration ("5m", "1h30m"), which is what a tenant
        // types into the source form -- the policy has to speak the same language or comparing
        // the two means nothing.
        assertTrue(PolicyType.DURATION.accepts("5m"));
        assertTrue(PolicyType.DURATION.accepts("1h"));
        assertTrue(PolicyType.DURATION.accepts("1h30m"));
        assertTrue(PolicyType.DURATION.accepts("45s"));
        assertFalse(PolicyType.DURATION.accepts("5"));
        assertFalse(PolicyType.DURATION.accepts("soon"));
        assertFalse(PolicyType.DURATION.accepts(""));
        assertEquals(300L, PolicyType.DURATION.parseDurationSeconds("5m"));
        assertEquals(5400L, PolicyType.DURATION.parseDurationSeconds("1h30m"));
        assertEquals(45L, PolicyType.DURATION.parseDurationSeconds("45s"));
    }

    @Test
    void stringListsAreCommaSeparatedAndTrimmed() {
        assertTrue(PolicyType.STRING_LIST.accepts("s3,push"));
        assertEquals(List.of("s3", "push"), PolicyType.STRING_LIST.parseStringList("s3, push"));
        assertEquals(List.of("s3"), PolicyType.STRING_LIST.parseStringList("s3"));
        // An empty list is a meaningful policy -- "no source type is allowed" -- so it parses
        // rather than being rejected as malformed.
        assertTrue(PolicyType.STRING_LIST.accepts(""));
        assertEquals(List.of(), PolicyType.STRING_LIST.parseStringList(""));
    }

    @Test
    void everyTypeRejectsNull() {
        for (PolicyType type : PolicyType.values()) {
            assertFalse(type.accepts(null), type + " accepted null");
        }
    }
}
