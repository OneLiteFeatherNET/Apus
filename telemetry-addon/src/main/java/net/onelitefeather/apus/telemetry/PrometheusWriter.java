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
 * Renders a {@link ProgressSnapshot} in the Prometheus text exposition format.
 *
 * <p>Unknown values are omitted entirely rather than exported as {@code -1}: a sentinel
 * would corrupt averages and alerting rules downstream.
 */
public final class PrometheusWriter {

    private PrometheusWriter() {}

    public static String toPrometheus(ProgressSnapshot snapshot) {
        StringBuilder out = new StringBuilder(512);
        String map = snapshot.currentMap();

        if (snapshot.progress() >= 0 && map != null) {
            out.append("# HELP apus_render_progress_ratio Progress of the current render task, 0 to 1.\n");
            out.append("# TYPE apus_render_progress_ratio gauge\n");
            out.append("apus_render_progress_ratio{map=\"").append(escapeLabel(map)).append("\"} ")
                    .append(Numbers.compact(snapshot.progress())).append('\n');
        }

        if (snapshot.etaSeconds() >= 0 && map != null) {
            out.append("# HELP apus_render_eta_seconds Estimated seconds until the current task finishes.\n");
            out.append("# TYPE apus_render_eta_seconds gauge\n");
            out.append("apus_render_eta_seconds{map=\"").append(escapeLabel(map)).append("\"} ")
                    .append(snapshot.etaSeconds()).append('\n');
        }

        if (snapshot.queuedTasks() >= 0) {
            out.append("# HELP apus_render_queued_tasks Number of scheduled render tasks.\n");
            out.append("# TYPE apus_render_queued_tasks gauge\n");
            out.append("apus_render_queued_tasks ").append(snapshot.queuedTasks()).append('\n');
        }

        if (snapshot.renderThreads() >= 0) {
            out.append("# HELP apus_render_threads Number of BlueMap render worker threads.\n");
            out.append("# TYPE apus_render_threads gauge\n");
            out.append("apus_render_threads ").append(snapshot.renderThreads()).append('\n');
        }

        out.append("# HELP apus_render_degraded 1 when progress could not be determined.\n");
        out.append("# TYPE apus_render_degraded gauge\n");
        out.append("apus_render_degraded ").append(snapshot.degraded() ? 1 : 0).append('\n');

        return out.toString();
    }

    private static String escapeLabel(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
