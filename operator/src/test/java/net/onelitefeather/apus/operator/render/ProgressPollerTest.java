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
package net.onelitefeather.apus.operator.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProgressPollerTest {

    @Test
    void parsesARunningRender() {
        String json =
                """
                {"state":"rendering","currentMap":"overworld","progress":0.72232,\
                "etaSeconds":28,"queuedTasks":-1,"renderThreads":-1,"degraded":false,\
                "description":"updating map 'overworld'"}""";

        Optional<ProgressPoller.RenderProgress> parsed = ProgressPoller.parse(json);

        assertTrue(parsed.isPresent());
        assertEquals("rendering", parsed.get().state());
        assertEquals("overworld", parsed.get().currentMap());
        assertEquals(0.72232, parsed.get().progress(), 1e-6);
        assertEquals(28L, parsed.get().etaSeconds());
        assertFalse(parsed.get().degraded());
    }

    @Test
    void parsesADegradedResponseWithoutFailing() {
        String json =
                """
                {"state":"unknown","currentMap":null,"progress":-1,"etaSeconds":-1,\
                "queuedTasks":-1,"renderThreads":-1,"degraded":true,"description":"no plugin"}""";

        Optional<ProgressPoller.RenderProgress> parsed = ProgressPoller.parse(json);

        assertTrue(parsed.isPresent());
        assertTrue(parsed.get().degraded());
        assertEquals(-1.0, parsed.get().progress(), 1e-9);
    }

    @Test
    void returnsEmptyForGarbageInsteadOfThrowing() {
        // The pod may be starting up, or something else may answer on that port.
        assertTrue(ProgressPoller.parse("not json at all").isEmpty());
        assertTrue(ProgressPoller.parse("").isEmpty());
    }
}
