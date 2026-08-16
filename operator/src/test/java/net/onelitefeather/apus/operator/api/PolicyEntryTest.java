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
package net.onelitefeather.apus.operator.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyEntryTest {

    @Test
    void aFreshSpecHasAnEmptyPolicyRatherThanNull() {
        // Every reader treats "no policy" as "unregulated"; a null here would make each of them
        // write the same null check, and one of them would eventually forget.
        assertNotNull(new TenantSpec().getPolicy());
        assertTrue(new TenantSpec().getPolicy().isEmpty());
    }

    @Test
    void anEntryCarriesKeyTypeValueAndLock() {
        PolicyEntry entry = new PolicyEntry();
        entry.setKey("source.poll.minimum");
        entry.setType("duration");
        entry.setValue("5m");
        entry.setLocked(true);

        assertEquals("source.poll.minimum", entry.getKey());
        assertEquals("duration", entry.getType());
        assertEquals("5m", entry.getValue());
        assertTrue(entry.isLocked());
    }

    @Test
    void anEntryIsUnlockedUntilSaid() {
        // The safe default: adding an option records intent without silently starting to refuse
        // a tenant's existing requests.
        assertFalse(new PolicyEntry().isLocked());
    }

    @Test
    void theSpecRoundTripsAPolicyList() {
        PolicyEntry entry = new PolicyEntry();
        entry.setKey("render.force.allowed");
        entry.setType("boolean");
        entry.setValue("false");

        TenantSpec spec = new TenantSpec();
        spec.setPolicy(List.of(entry));

        assertEquals(1, spec.getPolicy().size());
        assertEquals("render.force.allowed", spec.getPolicy().get(0).getKey());
    }

    @Test
    void settingNullPolicyClearsRatherThanNulls() {
        // Fabric8 deserialises an absent field as null; the setter has to absorb that or every
        // reader is back to null-checking.
        TenantSpec spec = new TenantSpec();
        spec.setPolicy(null);

        assertNotNull(spec.getPolicy());
        assertTrue(spec.getPolicy().isEmpty());
    }
}
