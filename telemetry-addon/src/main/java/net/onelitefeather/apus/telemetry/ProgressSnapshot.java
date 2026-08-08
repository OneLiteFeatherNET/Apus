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

/**
 * An immutable point-in-time view of BlueMap's render progress.
 *
 * <p>Unknown numeric values are represented as {@code -1} rather than {@code null}
 * so that consumers never have to null-check primitives.
 */
public record ProgressSnapshot(
        State state,
        String currentMap,
        double progress,
        long etaSeconds,
        int queuedTasks,
        int renderThreads,
        boolean degraded,
        String description) {

    public enum State {
        STARTING,
        RENDERING,
        IDLE,
        UNKNOWN
    }

    /**
     * Creates a snapshot for the case where progress could not be determined at all.
     * The render itself is unaffected; only the reporting degrades.
     */
    public static ProgressSnapshot unknown(String reason) {
        return new ProgressSnapshot(State.UNKNOWN, null, -1.0, -1L, -1, -1, true, reason);
    }

    /** Creates a snapshot for a running BlueMap that currently has no active task. */
    public static ProgressSnapshot idle(int queuedTasks, int renderThreads) {
        return new ProgressSnapshot(State.IDLE, null, -1.0, -1L, queuedTasks, renderThreads, false, null);
    }
}
