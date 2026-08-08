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
package net.onelitefeather.apus.telemetry.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.onelitefeather.apus.telemetry.ProgressSnapshot;
import org.junit.jupiter.api.Test;

class RenderProgressProbeTest {

    @Test
    void reportsRenderingWhenATaskIsActive() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.queued = 2;
        access.threads = 8;
        access.task = new RenderManagerAccess.TaskInfo("overworld", "Updating map 'overworld'", 0.674, 1_830_000L);

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(ProgressSnapshot.State.RENDERING, snapshot.state());
        assertEquals("overworld", snapshot.currentMap());
        assertEquals(0.674, snapshot.progress(), 1e-9);
        assertEquals(1830L, snapshot.etaSeconds());
        assertEquals(2, snapshot.queuedTasks());
        assertEquals(8, snapshot.renderThreads());
        assertFalse(snapshot.degraded());
    }

    @Test
    void reportsIdleWhenNoTaskIsRunning() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.queued = 0;
        access.threads = 4;
        access.task = null;

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(ProgressSnapshot.State.IDLE, snapshot.state());
        assertFalse(snapshot.degraded());
        assertEquals(4, snapshot.renderThreads());
    }

    @Test
    void reportsStartingWhileTheApiIsNotYetAvailable() {
        ProgressSnapshot snapshot = new RenderProgressProbe(() -> null).sample();

        assertEquals(ProgressSnapshot.State.STARTING, snapshot.state());
        assertFalse(snapshot.degraded(), "waiting for the API is normal, not a degradation");
    }

    @Test
    void degradesInsteadOfThrowingWhenBlueMapAccessFails() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.failWith = new NoSuchMethodError("getCurrentRenderTask");

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(ProgressSnapshot.State.UNKNOWN, snapshot.state());
        assertTrue(snapshot.degraded());
        assertTrue(snapshot.description().contains("getCurrentRenderTask"), snapshot.description());
    }

    @Test
    void treatsAnEtaOfZeroAsUnknown() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.task = new RenderManagerAccess.TaskInfo("nether", "Updating", 0.1, 0L);

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(-1L, snapshot.etaSeconds(), "BlueMap returns 0 when it cannot estimate");
    }

    @Test
    void survivesATaskWithoutAMapBinding() {
        FakeRenderManagerAccess access = new FakeRenderManagerAccess();
        access.task = new RenderManagerAccess.TaskInfo(null, "Saving map data", 0.9, 5000L);

        ProgressSnapshot snapshot = new RenderProgressProbe(() -> access).sample();

        assertEquals(ProgressSnapshot.State.RENDERING, snapshot.state());
        assertEquals(null, snapshot.currentMap());
        assertFalse(snapshot.degraded());
    }

    @Test
    void degradesInsteadOfThrowingWhenTheSupplierItselfFails() {
        ProgressSnapshot snapshot = new RenderProgressProbe(() -> {
                    throw new IllegalStateException("supplier exploded");
                })
                .sample();

        assertEquals(ProgressSnapshot.State.UNKNOWN, snapshot.state());
        assertTrue(snapshot.degraded());
        assertTrue(snapshot.description().contains("supplier exploded"), snapshot.description());
    }
}
