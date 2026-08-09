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

/**
 * Where {@code GET /api/renders/{id}/logs} reads log lines from. Two implementations exist,
 * chosen once at startup by {@link LogSourceFactory} depending on whether a Loki instance is
 * configured -- see that class, and the "log source" section of the task 3 report, for the
 * decision and its consequences for the API's ServiceAccount permissions.
 */
interface LogSource {

    /**
     * Starts tailing log lines for the given render's job, pushing each into {@code sink} as it
     * arrives. Returns a handle that stops the tail and releases whatever connection/thread it
     * holds when closed -- called by {@link SseSource} once the SSE stream ends, whether that is
     * because the client disconnected or the render became terminal.
     *
     * @param namespace the tenant namespace {@code jobName} lives in, already resolved and
     *     tenant-checked by the caller
     * @param jobName {@link net.onelitefeather.apus.operator.api.BlueMapRenderStatus#getJobName()}
     *     of the render being tailed
     * @param sink receives one {@link SseSource.Sink#next} call per log line, in arrival order
     */
    AutoCloseable tail(String namespace, String jobName, SseSource.Sink<String> sink);
}
