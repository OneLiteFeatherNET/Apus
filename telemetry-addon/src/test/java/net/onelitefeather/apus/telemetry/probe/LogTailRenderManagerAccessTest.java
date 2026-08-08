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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Exercises {@link LogTailRenderManagerAccess}'s line parser directly, via {@link
 * LogTailRenderManagerAccess#logInfo(String)}, without ever calling {@link
 * LogTailRenderManagerAccess#register()} -- so these tests never touch the shared, static
 * {@code Logger.global} registry and need no running BlueMap instance.
 *
 * <p>Every progress-line fixture below is either taken verbatim from a real render of
 * {@code testdata/mini-world} (the ETA case) or a minimal variation of it (see each test),
 * not invented from reading BlueMap's source.
 */
class LogTailRenderManagerAccessTest {

    @Test
    void parsesARealProgressLineWithAnEta() {
        LogTailRenderManagerAccess access = new LogTailRenderManagerAccess();

        access.logInfo("updating map 'overworld': 35.208% (ETA: 38 seconds)");

        RenderManagerAccess.TaskInfo task = access.currentTask();
        assertEquals("overworld", task.mapId());
        assertEquals("updating map 'overworld'", task.description());
        assertEquals(0.35208, task.progress(), 1e-9);
        assertEquals(38_000L, task.etaMillis());
        assertTrue(access.isRunning());
    }

    @Test
    void parsesAProgressLineWithoutAnEta() {
        LogTailRenderManagerAccess access = new LogTailRenderManagerAccess();

        access.logInfo("updating map 'overworld': 100.0%");

        RenderManagerAccess.TaskInfo task = access.currentTask();
        assertEquals("overworld", task.mapId());
        assertEquals(1.0, task.progress(), 1e-9);
        assertEquals(0L, task.etaMillis(), "no ETA in the line means 'unknown', reported as 0 like RenderManager does");
    }

    @Test
    void parsesADifferentMapName() {
        LogTailRenderManagerAccess access = new LogTailRenderManagerAccess();

        access.logInfo("updating map 'nether': 12.5% (ETA: 2 minutes)");

        RenderManagerAccess.TaskInfo task = access.currentTask();
        assertEquals("nether", task.mapId());
        assertEquals(0.125, task.progress(), 1e-9);
        assertEquals(120_000L, task.etaMillis());
    }

    @Test
    void ignoresALineThatDoesNotMatchTheProgressPattern() {
        LogTailRenderManagerAccess access = new LogTailRenderManagerAccess();
        access.logInfo("updating map 'overworld': 35.208% (ETA: 38 seconds)");

        access.logInfo("Your maps are now all up-to-date!");

        RenderManagerAccess.TaskInfo task = access.currentTask();
        assertEquals("overworld", task.mapId(), "an unrelated log line must not change the last known state");
        assertEquals(0.35208, task.progress(), 1e-9);
    }

    @Test
    void usesADotAsTheDecimalSeparatorRegardlessOfHostLocale() {
        LogTailRenderManagerAccess access = new LogTailRenderManagerAccess();

        access.logInfo("updating map 'overworld': 43.512% (ETA: 51 seconds)");

        assertEquals(0.43512, access.currentTask().progress(), 1e-9);
        assertEquals(51_000L, access.currentTask().etaMillis());
    }

    @Test
    void reportsNoTaskAndNotRunningBeforeAnyLineWasObserved() {
        LogTailRenderManagerAccess access = new LogTailRenderManagerAccess();

        assertNull(access.currentTask());
        assertFalse(access.isRunning());
    }

    @Test
    void reportsQueuedTasksAndRenderThreadsAsUnknown() {
        LogTailRenderManagerAccess access = new LogTailRenderManagerAccess();

        access.logInfo("updating map 'overworld': 10.0% (ETA: 5 seconds)");

        assertEquals(-1, access.queuedTasks(), "no log line ever carries queue depth");
        assertEquals(-1, access.renderThreads(), "no log line ever carries the thread count");
    }
}
