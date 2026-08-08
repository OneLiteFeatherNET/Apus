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
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JsonWriterTest {

    @Test
    void serialisesARunningRender() {
        ProgressSnapshot snapshot = new ProgressSnapshot(
                ProgressSnapshot.State.RENDERING, "overworld", 0.674, 1830L, 2, 8, false, "Updating map 'overworld'");

        String json = JsonWriter.toJson(snapshot);

        assertEquals(
                "{\"state\":\"rendering\",\"currentMap\":\"overworld\",\"progress\":0.674,"
                        + "\"etaSeconds\":1830,\"queuedTasks\":2,\"renderThreads\":8,"
                        + "\"degraded\":false,\"description\":\"Updating map 'overworld'\"}",
                json);
    }

    @Test
    void serialisesNullFieldsAsJsonNull() {
        String json = JsonWriter.toJson(ProgressSnapshot.unknown("no plugin"));

        assertTrue(json.contains("\"currentMap\":null"), json);
        assertTrue(json.contains("\"state\":\"unknown\""), json);
        assertTrue(json.contains("\"degraded\":true"), json);
    }

    @Test
    void escapesQuotesAndBackslashesInDescriptions() {
        ProgressSnapshot snapshot = ProgressSnapshot.unknown("say \"hi\" \\ bye");

        String json = JsonWriter.toJson(snapshot);

        assertTrue(json.contains("\"description\":\"say \\\"hi\\\" \\\\ bye\""), json);
    }
}
