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

/**
 * The single seam between Apus and BlueMap's internals.
 *
 * <p>Everything above this interface is plain Java and unit-testable without a running
 * BlueMap. Only {@link BlueMapRenderManagerAccess} imports BlueMap types, which keeps the
 * blast radius of a BlueMap upgrade to exactly one class.
 */
public interface RenderManagerAccess {

    boolean isRunning();

    int queuedTasks();

    int renderThreads();

    /**
     * @return information about the currently running task, or {@code null} when idle
     */
    TaskInfo currentTask();

    /**
     * @param mapId       id of the map being rendered, or {@code null} if the task is not map-bound
     * @param description human-readable task description as provided by BlueMap
     * @param progress    completion between 0 and 1
     * @param etaMillis   estimated milliseconds remaining, 0 when BlueMap cannot estimate
     */
    record TaskInfo(String mapId, String description, double progress, long etaMillis) {}
}
