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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.Namespaced;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class HostingResourceTest {

    @Test
    void hostingIsNamespaced() {
        // A hosting webserver belongs to exactly one tenant's namespace, exactly like
        // BlueMapMap and WorldSource.
        assertTrue(Namespaced.class.isAssignableFrom(BlueMapHosting.class));
    }

    @Test
    void everyResourceHasANonNullSpecAndStatusRightAfterConstruction() {
        // CustomResource's default initSpec()/initStatus() return null; a subclass has to
        // override both or `new X().getSpec()` is null until something (e.g. Jackson
        // deserialisation from the API server) overwrites the field. This trap already blocked
        // three parallel Phase 2a tasks once -- checked explicitly here so it cannot repeat.
        assertNotNull(
                new BlueMapHosting().getSpec(), "BlueMapHosting.getSpec() must not be null right after construction");
        assertNotNull(
                new BlueMapHosting().getStatus(),
                "BlueMapHosting.getStatus() must not be null right after construction");
    }

    @Test
    void specGroupsAreInitialisedSoReconcilersNeverSeeNull() {
        BlueMapHosting hosting = new BlueMapHosting();
        assertNotNull(hosting.getSpec().getMaps());
        assertNotNull(hosting.getSpec().getTls());
        assertNotNull(hosting.getSpec().getResources());
        assertNotNull(hosting.getStatus().getConditions());
    }

    @Test
    void defaultsMatchTheSpecifiedProductionShape() {
        BlueMapHostingSpec spec = new BlueMapHosting().getSpec();

        assertEquals("nginx", spec.getIngressClassName());
        assertEquals(1, spec.getReplicas());
        assertTrue(spec.getTls().isEnabled());
        assertEquals("ClusterIssuer", spec.getTls().getIssuerKind());
    }

    @Test
    void allNestedGroupsAreInitialisedRecursively() {
        // A check that only looks at the first level of a spec/status (as
        // specGroupsAreInitialisedSoReconcilersNeverSeeNull() above does) can miss a group
        // nested two levels deep, e.g. Tls.issuerRef. Walking every nested Apus-owned group
        // recursively closes that gap for the current fields and for any added later -- see
        // IngestResourceTest for the identical check on the Phase 2b resources, and the Phase
        // 2a incident that motivated it.
        assertNoUninitialisedGroup(new BlueMapHosting().getSpec());
        assertNoUninitialisedGroup(new BlueMapHosting().getStatus());
    }

    private static void assertNoUninitialisedGroup(Object group) {
        for (Field field : group.getClass().getDeclaredFields()) {
            if (field.isSynthetic() || !isApusOwnedType(field.getType())) {
                continue;
            }
            field.setAccessible(true);
            Object value;
            try {
                value = field.get(group);
            } catch (IllegalAccessException e) {
                throw new AssertionError("could not read field " + field, e);
            }
            assertNotNull(
                    value,
                    group.getClass().getSimpleName() + "." + field.getName()
                            + " must be initialised in its field declaration, not left null");
            assertNoUninitialisedGroup(value);
        }
    }

    private static boolean isApusOwnedType(Class<?> type) {
        // Nested static classes (e.g. BlueMapHostingSpec.Tls) still report their enclosing
        // top-level class's package, so a plain equality check also covers them.
        return "net.onelitefeather.apus.operator.api".equals(type.getPackageName());
    }
}
