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
package net.onelitefeather.apus.api.events;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RenderPhasesTest {

    @Test
    void succeededIsTerminal() {
        assertTrue(RenderPhases.isTerminal("Succeeded"));
    }

    @Test
    void failedIsTerminal() {
        assertTrue(RenderPhases.isTerminal("Failed"));
    }

    @Test
    void pendingSyncingRenderingFinalizingAreNotTerminal() {
        for (String phase : new String[] {"Pending", "Syncing", "Rendering", "Finalizing"}) {
            assertFalse(RenderPhases.isTerminal(phase), () -> phase + " must not be terminal");
        }
    }

    @Test
    void nullPhaseIsNotTerminal() {
        // Not yet set by the operator -- must not be mistaken for "done".
        assertFalse(RenderPhases.isTerminal(null));
    }

    @Test
    void unknownPhaseIsNotTerminal() {
        assertFalse(RenderPhases.isTerminal("SomethingElse"));
    }
}
