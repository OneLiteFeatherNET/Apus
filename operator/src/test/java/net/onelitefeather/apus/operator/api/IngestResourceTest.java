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

class IngestResourceTest {

    @Test
    void bothResourcesAreNamespaced() {
        assertTrue(Namespaced.class.isAssignableFrom(WorldSource.class));
        assertTrue(Namespaced.class.isAssignableFrom(WorldIngest.class));
    }

    @Test
    void specGroupsAreInitialisedSoReconcilersNeverSeeNull() {
        WorldSource source = new WorldSource();
        assertNotNull(source.getSpec().getS3());
        assertNotNull(source.getSpec().getPterodactyl());
        assertNotNull(source.getSpec().getWorlds());
        assertNotNull(source.getSpec().getRetention());
        assertNotNull(source.getStatus().getLatestBundle());

        WorldIngest ingest = new WorldIngest();
        assertNotNull(ingest.getSpec().getSourceRef());
        assertNotNull(ingest.getStatus().getProgress());
        assertNotNull(ingest.getStatus().getBundle());
    }

    @Test
    void retentionDefaultsToFiveVersions() {
        assertEquals(5, new WorldSource().getSpec().getRetention().getKeepVersions());
    }

    @Test
    void layoutDefaultsToAutomaticDetection() {
        WorldSource.WorldSelector selector = new WorldSource.WorldSelector();
        assertEquals("auto", selector.getLayout());
    }

    @Test
    void everyResourceHasANonNullSpecAndStatusRightAfterConstruction() {
        // CustomResource's default initSpec()/initStatus() return null; a subclass has to
        // override both or `new X().getSpec()` is null until something (e.g. Jackson
        // deserialisation from the API server) overwrites the field. This trap already blocked
        // three parallel Phase 2a tasks once -- checked explicitly here so it cannot repeat.
        assertNotNull(new WorldSource().getSpec(), "WorldSource.getSpec() must not be null right after construction");
        assertNotNull(
                new WorldSource().getStatus(), "WorldSource.getStatus() must not be null right after construction");
        assertNotNull(
                new WorldIngest().getSpec(), "WorldIngest.getSpec() must not be null right after construction");
        assertNotNull(
                new WorldIngest().getStatus(), "WorldIngest.getStatus() must not be null right after construction");
    }

    @Test
    void allNestedGroupsAreInitialisedRecursivelyForBothResources() {
        // A check that only looks at the first level of a spec/status (as
        // specGroupsAreInitialisedSoReconcilersNeverSeeNull() above does) can miss a group
        // nested two levels deep, e.g. S3Source.credentialsSecretRef. Walking every nested
        // Apus-owned group recursively closes that gap for the current fields and for any
        // added later -- see ApusResourceTest for the identical check on the Phase 2a
        // resources, and the Phase 2a incident that motivated it.
        assertNoUninitialisedGroup(new WorldSource().getSpec());
        assertNoUninitialisedGroup(new WorldSource().getStatus());
        assertNoUninitialisedGroup(new WorldIngest().getSpec());
        assertNoUninitialisedGroup(new WorldIngest().getStatus());
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
        // Nested static classes (e.g. WorldSourceSpec.S3Source) still report their enclosing
        // top-level class's package, so a plain equality check also covers them.
        return "net.onelitefeather.apus.operator.api".equals(type.getPackageName());
    }
}
