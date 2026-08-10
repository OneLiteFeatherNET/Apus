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
package net.onelitefeather.apus.operator.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class IngestLogProgressTest {

    @Test
    void extractsTheLastPhaseLine() {
        String log = """
                [apus-ingest] phase=Pending source=s3 world=world bundle=t/w/v1
                [apus-ingest] phase=Extracting
                [apus-ingest] phase=Transforming
                """;

        IngestLogProgress progress = IngestLogProgress.parse(log);

        assertEquals("Transforming", progress.phase());
    }

    @Test
    void extractsTheLastProgressLine() {
        String log = """
                [apus-ingest] phase=Loading
                [apus-ingest] progress: 10.0% (100/1000 bytes)
                [apus-ingest] progress: 55.5% (555/1000 bytes)
                """;

        IngestLogProgress progress = IngestLogProgress.parse(log);

        assertEquals(55.5, progress.percent());
        assertEquals(555L, progress.bytesDone());
        assertEquals(1000L, progress.bytesTotal());
    }

    @Test
    void extractsDimensionsFromTheDetectedLayoutLine() {
        String log = "[apus-ingest] detected layout kind=bukkit dimensions=[overworld, the_nether, the_end]";

        IngestLogProgress progress = IngestLogProgress.parse(log);

        assertEquals(List.of("overworld", "the_nether", "the_end"), progress.dimensions());
    }

    @Test
    void emptyOrMissingLinesYieldNullFieldsRatherThanGuessedValues() {
        IngestLogProgress progress = IngestLogProgress.parse("");

        assertNull(progress.phase());
        assertNull(progress.percent());
        assertTrue(progress.dimensions().isEmpty());
    }

    @Test
    void nullLogYieldsAllNullFieldsWithoutThrowing() {
        IngestLogProgress progress = IngestLogProgress.parse(null);

        assertNull(progress.phase());
        assertTrue(progress.dimensions().isEmpty());
    }

    @Test
    void recognisesTheFinalSucceededPhaseAlongsideItsBundlePathSuffix() {
        String log = "[apus-ingest] phase=Succeeded bundlePath=acme/survival/v1";

        assertEquals("Succeeded", IngestLogProgress.parse(log).phase());
    }
}
