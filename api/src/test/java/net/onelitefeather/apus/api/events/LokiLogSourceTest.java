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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit-tests {@link LokiLogSource#parseStreams(String)} against a canned response body -- the
 * only part of {@link LokiLogSource} that does not need a live Loki instance to exercise, and the
 * part most likely to have an off-by-one/ordering bug (merging and sorting several concurrent
 * streams by timestamp).
 */
class LokiLogSourceTest {

    @Test
    void parsesAndOrdersLinesAcrossMultipleStreamsByTimestamp() throws Exception {
        // Two streams (e.g. two containers/pods), values deliberately out of chronological order
        // relative to each other -- the merge must still produce one globally time-ordered list.
        String json =
                """
                {
                  "status": "success",
                  "data": {
                    "resultType": "streams",
                    "result": [
                      {
                        "stream": {"pod": "render-abc-1"},
                        "values": [
                          ["100", "first"],
                          ["300", "third"]
                        ]
                      },
                      {
                        "stream": {"pod": "render-abc-1"},
                        "values": [
                          ["200", "second"]
                        ]
                      }
                    ]
                  }
                }
                """;

        List<LokiLogSource.LogLine> lines = LokiLogSource.parseStreams(json);

        assertEquals(
                List.of("first", "second", "third"),
                lines.stream().map(LokiLogSource.LogLine::text).toList());
        assertEquals(List.of(100L, 200L, 300L), lines.stream().map(LokiLogSource.LogLine::timestampNanos).toList());
    }

    @Test
    void emptyResultProducesNoLines() throws Exception {
        String json =
                """
                {"status": "success", "data": {"resultType": "streams", "result": []}}
                """;

        assertTrue(LokiLogSource.parseStreams(json).isEmpty());
    }
}
