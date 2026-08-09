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
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProgressSnapshotTest {

    @Test
    void unknownMarksItselfDegradedAndCarriesTheReason() {
        ProgressSnapshot snapshot = ProgressSnapshot.unknown("plugin() returned null");

        assertEquals(ProgressSnapshot.State.UNKNOWN, snapshot.state());
        assertTrue(snapshot.degraded());
        assertEquals(-1.0, snapshot.progress());
        assertEquals(-1L, snapshot.etaSeconds());
        assertEquals("plugin() returned null", snapshot.description());
        assertNull(snapshot.currentMap());
    }

    @Test
    void idleReportsQueueAndThreadsButNoProgress() {
        ProgressSnapshot snapshot = ProgressSnapshot.idle(3, 8);

        assertEquals(ProgressSnapshot.State.IDLE, snapshot.state());
        assertFalse(snapshot.degraded());
        assertEquals(3, snapshot.queuedTasks());
        assertEquals(8, snapshot.renderThreads());
        assertEquals(-1.0, snapshot.progress());
    }
}
