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
package net.onelitefeather.apus.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.http.filter.ServerFilterPhase;
import org.junit.jupiter.api.Test;

/**
 * The filter must run after Micronaut's security filter, because it reads the {@code
 * Authentication} that filter puts on the request. Ordered ahead of it, it would find none and
 * refuse every impersonated request -- a failure that looks like a permission problem and sends
 * somebody looking at roles.
 *
 * <p>The order has to be a literal, because an annotation value must be a constant expression and
 * {@code ServerFilterPhase.SECURITY.after()} is a method call. This test is what keeps the
 * literal honest: a Micronaut release that renumbers the phases fails here rather than silently
 * reordering the filter.
 */
class ImpersonationFilterOrderTest {

    @Test
    void runsImmediatelyAfterTheSecurityFilter() {
        assertEquals(ServerFilterPhase.SECURITY.after(), ImpersonationFilter.ORDER);
    }

    @Test
    void andThereforeAfterSecurityItself() {
        // Stated separately from the equality above: if the phase numbering ever changed such
        // that `after()` no longer sorted after `order()`, the first test could still pass while
        // the filter ran too early.
        assertTrue(
                ImpersonationFilter.ORDER > ServerFilterPhase.SECURITY.order(),
                "impersonation must be applied once the caller is authenticated, never before");
    }
}
