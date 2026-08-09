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
package net.onelitefeather.apus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.Test;

class ThrottledProgressSinkTest {

    @Test
    void printsOnTheFirstUpdateThenSuppressesUntilTheIntervalElapses() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        // One fixed instant per call, so every update looks simultaneous -- none but the first
        // and the (necessarily final, bytesDone == bytesTotal) update should print.
        Instant frozen = Instant.parse("2026-08-08T00:00:00Z");
        ThrottledProgressSink sink = new ThrottledProgressSink(Duration.ofSeconds(10), () -> frozen, printStream(buffer));

        sink.update(10, 100);
        sink.update(20, 100);
        sink.update(30, 100);

        List<String> lines = lines(buffer);
        assertEquals(1, lines.size(), "only the first call may print while the clock stands still: " + lines);
        assertTrue(lines.get(0).contains("10/100"));
    }

    @Test
    void printsAgainOnceTheIntervalHasElapsed() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Iterator<Instant> ticks = List.of(
                        Instant.parse("2026-08-08T00:00:00Z"), // first update: always prints
                        Instant.parse("2026-08-08T00:00:03Z"), // +3s, interval is 10s: suppressed
                        Instant.parse("2026-08-08T00:00:11Z")) // +11s since last print: prints
                .iterator();
        ThrottledProgressSink sink = new ThrottledProgressSink(Duration.ofSeconds(10), ticks::next, printStream(buffer));

        sink.update(10, 1000);
        sink.update(20, 1000);
        sink.update(30, 1000);

        List<String> lines = lines(buffer);
        assertEquals(2, lines.size(), "first update and the one 11s later, not the one in between: " + lines);
        assertTrue(lines.get(0).contains("10/1000"));
        assertTrue(lines.get(1).contains("30/1000"));
    }

    @Test
    void alwaysPrintsTheFinalUpdateEvenWithinTheThrottleWindow() {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Instant frozen = Instant.parse("2026-08-08T00:00:00Z");
        ThrottledProgressSink sink = new ThrottledProgressSink(Duration.ofMinutes(5), () -> frozen, printStream(buffer));

        sink.update(50, 100);
        sink.update(100, 100); // final, despite zero elapsed time on the frozen clock

        List<String> lines = lines(buffer);
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("100.0%"));
        assertTrue(lines.get(1).contains("100/100"));
    }

    private static PrintStream printStream(ByteArrayOutputStream buffer) {
        return new PrintStream(buffer, true, StandardCharsets.UTF_8);
    }

    private static List<String> lines(ByteArrayOutputStream buffer) {
        String text = buffer.toString(StandardCharsets.UTF_8);
        return text.isEmpty() ? List.of() : List.of(text.strip().split("\\R"));
    }
}
