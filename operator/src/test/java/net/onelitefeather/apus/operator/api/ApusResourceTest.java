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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApusResourceTest {

    @Test
    void bothResourcesAreNamespaced() {
        // Only Tenant is cluster-scoped: it hands out a namespace and a quota.
        // Maps and renders belong to exactly one tenant and must never escape it.
        assertTrue(io.fabric8.kubernetes.api.model.Namespaced.class.isAssignableFrom(BlueMapMap.class));
        assertTrue(io.fabric8.kubernetes.api.model.Namespaced.class.isAssignableFrom(BlueMapRender.class));
    }

    @Test
    void referencesCarryNoNamespace() throws Exception {
        // §10.1: a resource may only reference things in its own namespace.
        // A namespace field on Ref would invite exactly the cross-tenant reference
        // the design forbids.
        for (java.lang.reflect.Field field : Ref.class.getDeclaredFields()) {
            assertNotEquals("namespace", field.getName(), "Ref must not carry a namespace — see spec §10.1");
        }
    }

    @Test
    void specGroupsAreInitialisedSoReconcilersNeverSeeNull() {
        BlueMapMap map = new BlueMapMap();
        assertNotNull(map.getSpec().getSource());
        assertNotNull(map.getSpec().getTrigger());
        assertNotNull(map.getSpec().getStorage());
        assertNotNull(map.getStatus().getBucket());
    }

    @Test
    void concurrencyPolicyDefaultsToForbid() {
        // Two renders writing the same map storage can leave the map inconsistent (§7.3).
        assertEquals("Forbid", new BlueMapMap().getSpec().getTrigger().getConcurrencyPolicy());
    }
}
