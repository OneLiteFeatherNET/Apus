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

/** A hand-written test double; BlueMap's RenderManager is a concrete class and cannot be mocked cleanly. */
final class FakeRenderManagerAccess implements RenderManagerAccess {

    boolean running = true;
    int queued = 0;
    int threads = 4;
    TaskInfo task = null;
    Error failWith = null;

    @Override
    public boolean isRunning() {
        if (failWith != null) throw failWith;
        return running;
    }

    @Override
    public int queuedTasks() {
        if (failWith != null) throw failWith;
        return queued;
    }

    @Override
    public int renderThreads() {
        if (failWith != null) throw failWith;
        return threads;
    }

    @Override
    public TaskInfo currentTask() {
        if (failWith != null) throw failWith;
        return task;
    }
}
