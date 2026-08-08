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

import java.io.PrintStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * A {@link BundleWriter.ProgressSink} that prints a plain-text progress line to a stream, at most
 * once per {@code minInterval}, plus unconditionally on the final update.
 *
 * <p>The ingest job is short-lived and has no HTTP server (unlike {@code runner}'s telemetry
 * addon) -- see {@code ingest/README.md} for why a periodic stdout line was chosen instead. {@link
 * BundleWriter} may call {@link #update} once per region file, which for a large world can be
 * thousands of times in quick succession; without throttling that would flood the job's log
 * output without adding useful information.
 */
final class ThrottledProgressSink implements BundleWriter.ProgressSink {

    private final Duration minInterval;
    private final Supplier<Instant> clock;
    private final PrintStream out;
    private Instant lastReported;

    ThrottledProgressSink(Duration minInterval) {
        this(minInterval, Instant::now, System.out);
    }

    /** Visible for tests to inject a fake clock and capture output without a real sleep. */
    ThrottledProgressSink(Duration minInterval, Supplier<Instant> clock, PrintStream out) {
        this.minInterval = minInterval;
        this.clock = clock;
        this.out = out;
        this.lastReported = null;
    }

    @Override
    public void update(long bytesDone, long bytesTotal) {
        Instant now = clock.get();
        boolean isFinal = bytesTotal <= 0 || bytesDone >= bytesTotal;
        boolean intervalElapsed =
                lastReported == null || Duration.between(lastReported, now).compareTo(minInterval) >= 0;
        if (!isFinal && !intervalElapsed) {
            return;
        }
        double percent = bytesTotal > 0 ? (100.0 * bytesDone / bytesTotal) : 100.0;
        out.printf(Locale.ROOT, "[apus-ingest] progress: %.1f%% (%d/%d bytes)%n", percent, bytesDone, bytesTotal);
        lastReported = now;
    }
}
