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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PrometheusWriterTest {

    @Test
    void emitsHelpTypeAndValueForARunningRender() {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "overworld", 0.5, 60L, 1, 4, false, "Updating");

        String text = PrometheusWriter.toPrometheus(snapshot);

        assertTrue(text.contains("# TYPE apus_render_progress_ratio gauge"), text);
        assertTrue(text.contains("apus_render_progress_ratio{map=\"overworld\"} 0.5"), text);
        assertTrue(text.contains("apus_render_eta_seconds{map=\"overworld\"} 60"), text);
        assertTrue(text.contains("apus_render_queued_tasks 1"), text);
        assertTrue(text.contains("apus_render_threads 4"), text);
        assertTrue(text.contains("apus_render_degraded 0"), text);
        assertTrue(text.endsWith("\n"), "Prometheus exposition format must end with a newline");
    }

    @Test
    void omitsUnknownValuesInsteadOfEmittingMinusOne() {
        String text = PrometheusWriter.toPrometheus(ProgressSnapshot.unknown("no plugin"));

        assertFalse(text.contains("apus_render_progress_ratio{"), text);
        assertFalse(text.contains("-1"), text);
        assertTrue(text.contains("apus_render_degraded 1"), text);
    }

    @Test
    void escapesSpecialCharactersInMapLabels() {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "map\"with\\backslash", 0.5, 60L, 1, 4, false, "Updating");

        String text = PrometheusWriter.toPrometheus(snapshot);

        assertTrue(text.contains("apus_render_progress_ratio{map=\"map\\\"with\\\\backslash\"} 0.5"), text);
        assertTrue(text.contains("apus_render_eta_seconds{map=\"map\\\"with\\\\backslash\"} 60"), text);
    }
}
