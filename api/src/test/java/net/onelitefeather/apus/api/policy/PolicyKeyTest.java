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

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PolicyKeyTest {

    @Test
    void theFourEnforceableKeysAreNamedAndTypedAsTheSpecSays() {
        // These strings are stored in Tenant manifests and typed into the console. Changing one
        // silently un-enforces every entry already written with the old spelling.
        assertEquals("source.types.allowed", PolicyKey.SOURCE_TYPES_ALLOWED.key());
        assertEquals(PolicyType.STRING_LIST, PolicyKey.SOURCE_TYPES_ALLOWED.type());

        assertEquals("source.poll.minimum", PolicyKey.SOURCE_POLL_MINIMUM.key());
        assertEquals(PolicyType.DURATION, PolicyKey.SOURCE_POLL_MINIMUM.type());

        assertEquals("source.keepVersions.maximum", PolicyKey.SOURCE_KEEP_VERSIONS_MAXIMUM.key());
        assertEquals(PolicyType.INTEGER, PolicyKey.SOURCE_KEEP_VERSIONS_MAXIMUM.type());

        assertEquals("render.force.allowed", PolicyKey.RENDER_FORCE_ALLOWED.key());
        assertEquals(PolicyType.BOOLEAN, PolicyKey.RENDER_FORCE_ALLOWED.type());
    }

    @Test
    void anUnknownKeyIsNotEnforcedAndIsNotAnError() {
        // The whole point of the generic bag: recording an intended rule ahead of the code that
        // applies it must be possible, and must be visibly unenforced rather than rejected.
        assertEquals(Optional.empty(), PolicyKey.fromKey("render.concurrency.maximum"));
        assertFalse(PolicyKey.isEnforced("render.concurrency.maximum"));
        assertFalse(PolicyKey.isEnforced(null));
        assertTrue(PolicyKey.isEnforced("render.force.allowed"));
    }

    @Test
    void everyKeyExplainsItself() {
        // The console shows this next to the input. A key with no sentence is a key nobody can
        // use correctly without reading Java.
        for (PolicyKey key : PolicyKey.values()) {
            assertFalse(key.description().isBlank(), key + " has no description");
        }
    }
}
