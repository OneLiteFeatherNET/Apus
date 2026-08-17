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

import java.lang.reflect.Field;
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
    void sourceRefIsInitialisedLikeEveryOtherRefField() {
        // Regression: BlueMapMapSpec.Source.sourceRef was left without a default value while
        // the structurally identical BlueMapRenderSpec.mapRef was not -- new
        // BlueMapMap().getSpec().getSource().getSourceRef() used to be null.
        assertNotNull(new BlueMapMap().getSpec().getSource().getSourceRef());
    }

    @Test
    void allNestedGroupsAreInitialisedRecursivelyForEveryResource() {
        // A check that only looks at the first level of a spec/status (as
        // specGroupsAreInitialisedSoReconcilersNeverSeeNull() above does) would have let
        // Source.sourceRef's missing default slip through, because Source itself was
        // non-null -- only its own field wasn't. Walking every nested Apus-owned group
        // recursively closes that gap for the current fields and for any added later.
        assertNoUninitialisedGroup(new Tenant().getSpec());
        assertNoUninitialisedGroup(new Tenant().getStatus());
        assertNoUninitialisedGroup(new BlueMapMap().getSpec());
        assertNoUninitialisedGroup(new BlueMapMap().getStatus());
        assertNoUninitialisedGroup(new BlueMapRender().getSpec());
        assertNoUninitialisedGroup(new BlueMapRender().getStatus());
    }

    /**
     * Recursively asserts that every field of {@code group} that is itself a class owned by
     * this package (a nested "group" such as {@code BlueMapMapSpec.Source}, or another
     * top-level model class such as {@link Ref}) is non-null, and recurses into it. Fields of
     * unrelated types (String, primitives, List, Map, ...) are left alone -- they are
     * intentionally allowed to be null/empty and are not "groups" in the sense the spec docs
     * use the word.
     */
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
        // Nested static classes (e.g. BlueMapMapSpec.Source) still report their enclosing
        // top-level class's package, so a plain equality check also covers them.
        return "net.onelitefeather.apus.operator.api".equals(type.getPackageName());
    }

    @Test
    void concurrencyPolicyDefaultsToForbid() {
        // Two renders writing the same map storage can leave the map inconsistent (§7.3).
        assertEquals("Forbid", new BlueMapMap().getSpec().getTrigger().getConcurrencyPolicy());
    }

    @Test
    void everyResourceHasANonNullSpecAndStatusRightAfterConstruction() {
        // CustomResource's default initSpec()/initStatus() return null; a subclass has to
        // override both or `new X().getSpec()` is null until something (e.g. Jackson
        // deserialisation from the API server) overwrites the field. Checked across all three
        // resources in one test so a fourth resource added later can't quietly skip this.
        assertNotNull(new Tenant().getSpec(), "Tenant.getSpec() must not be null right after construction");
        assertNotNull(new Tenant().getStatus(), "Tenant.getStatus() must not be null right after construction");
        assertNotNull(new BlueMapMap().getSpec(), "BlueMapMap.getSpec() must not be null right after construction");
        assertNotNull(
                new BlueMapMap().getStatus(), "BlueMapMap.getStatus() must not be null right after construction");
        assertNotNull(
                new BlueMapRender().getSpec(), "BlueMapRender.getSpec() must not be null right after construction");
        assertNotNull(
                new BlueMapRender().getStatus(),
                "BlueMapRender.getStatus() must not be null right after construction");
    }

    @Test
    void aTenantStartsWithNoRedirectUris() {
        // A tenant with no application instance must report an empty list, never null: every
        // reader of this field would otherwise have to guard, and the console renders nothing
        // at all rather than an empty "redirect URIs" box.
        assertTrue(new TenantStatus().getRedirectUris().isEmpty());
    }

    @Test
    void aTenantStatusAbsorbsNullRedirectUris() {
        // `kubectl edit` and a round-trip through Jackson can both put null here.
        TenantStatus status = new TenantStatus();

        status.setRedirectUris(null);

        assertTrue(status.getRedirectUris().isEmpty());
    }
}
