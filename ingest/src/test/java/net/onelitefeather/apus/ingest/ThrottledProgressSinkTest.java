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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The throttling itself, and the exact wording of the line it emits.
 *
 * <p>The wording matters beyond legibility: the operator has no way to see inside a running ingest
 * Job, so {@code WorldIngestReconciler} recovers the run's progress by matching {@code progress:
 * NN.N% (done/total bytes)} in the pod's log ({@code IngestLogProgress}). These assertions are what
 * stops a well-meant rewording from silently freezing the {@code WorldIngest}'s reported progress.
 */
class ThrottledProgressSinkTest {

    private ch.qos.logback.classic.Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureLog() {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ThrottledProgressSink.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void logsOnTheFirstUpdateThenSuppressesUntilTheIntervalElapses() {
        // One fixed instant per call, so every update looks simultaneous -- none but the first
        // and the (necessarily final, bytesDone == bytesTotal) update should be logged.
        Instant frozen = Instant.parse("2026-08-08T00:00:00Z");
        ThrottledProgressSink sink = new ThrottledProgressSink(Duration.ofSeconds(10), () -> frozen);

        sink.update(10, 100);
        sink.update(20, 100);
        sink.update(30, 100);

        List<String> lines = lines();
        assertEquals(1, lines.size(), "only the first call may log while the clock stands still: " + lines);
        assertTrue(lines.get(0).contains("10/100"));
    }

    @Test
    void logsAgainOnceTheIntervalHasElapsed() {
        Iterator<Instant> ticks = List.of(
                        Instant.parse("2026-08-08T00:00:00Z"), // first update: always logs
                        Instant.parse("2026-08-08T00:00:03Z"), // +3s, interval is 10s: suppressed
                        Instant.parse("2026-08-08T00:00:11Z")) // +11s since the last line: logs
                .iterator();
        ThrottledProgressSink sink = new ThrottledProgressSink(Duration.ofSeconds(10), ticks::next);

        sink.update(10, 1000);
        sink.update(20, 1000);
        sink.update(30, 1000);

        List<String> lines = lines();
        assertEquals(2, lines.size(), "first update and the one 11s later, not the one in between: " + lines);
        assertTrue(lines.get(0).contains("10/1000"));
        assertTrue(lines.get(1).contains("30/1000"));
    }

    @Test
    void alwaysLogsTheFinalUpdateEvenWithinTheThrottleWindow() {
        Instant frozen = Instant.parse("2026-08-08T00:00:00Z");
        ThrottledProgressSink sink = new ThrottledProgressSink(Duration.ofMinutes(5), () -> frozen);

        sink.update(50, 100);
        sink.update(100, 100); // final, despite zero elapsed time on the frozen clock

        List<String> lines = lines();
        assertEquals(2, lines.size());
        assertTrue(lines.get(1).contains("100.0%"));
        assertTrue(lines.get(1).contains("100/100"));
    }

    /**
     * The whole point of the line: it is logged at INFO (a business event an operator watches) and
     * in the exact shape {@code IngestLogProgress}'s regex matches.
     */
    @Test
    void logsAtInfoInTheShapeTheOperatorParses() {
        ThrottledProgressSink sink = new ThrottledProgressSink(Duration.ZERO);

        sink.update(555, 1000);

        ILoggingEvent event = appender.list.get(0);
        assertEquals(Level.INFO, event.getLevel());
        assertEquals("progress: 55.5% (555/1000 bytes)", event.getFormattedMessage());
    }

    private List<String> lines() {
        List<String> messages = new ArrayList<>();
        for (ILoggingEvent event : appender.list) {
            messages.add(event.getFormattedMessage());
        }
        return messages;
    }
}
