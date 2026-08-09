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
package net.onelitefeather.apus.telemetry.probe;

import de.bluecolored.bluemap.core.logger.AbstractLogger;
import de.bluecolored.bluemap.core.logger.Logger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads render progress off BlueMap's own logger instead of its (in CLI mode, unreachable)
 * internal {@code RenderManager}.
 *
 * <p>BlueMap's CLI always constructs its {@code BlueMapAPIImpl} with a {@code null} {@code
 * Plugin} (see {@link BlueMapRenderManagerAccess}'s javadoc and {@code
 * runner/README.md#telemetry} for how that was established), so neither the documented
 * addon route nor a reflection fallback ever reaches the real render manager in that mode.
 * BlueMap's own CLI, however, drives a periodic {@code TimerTask} that logs the exact same
 * progress it reads from that render manager, via {@code Logger.global}
 * ({@code de.bluecolored.bluemap.core.logger.Logger}) — a documented, addon-facing extension
 * point (the CLI's own {@code -l/--log-file} and {@code -b/--verbose} flags register
 * additional loggers on it the same way). Registering a logger there, early during addon
 * startup (before any render begins), makes every log line BlueMap produces visible to this
 * class, including the one line that matters:
 *
 * <pre>{@code updating map 'overworld': 35.208% (ETA: 38 seconds)}</pre>
 *
 * <p>This gives {@code mapId}, {@code progress}, and {@code etaMillis} — the fields {@link
 * RenderManagerAccess.TaskInfo} needs. There is no log line carrying queue depth or thread
 * count, so {@link #queuedTasks()} and {@link #renderThreads()} report {@code -1} (unknown)
 * rather than guessing.
 *
 * <p>Registration is a distinct step from construction ({@link #register()}) so unit tests
 * can exercise the line parser via {@link #logInfo(String)} directly without ever touching
 * the shared, static {@code Logger.global} registry.
 */
public final class LogTailRenderManagerAccess extends AbstractLogger implements RenderManagerAccess {

    /** The key this logger registers itself under on {@code Logger.global}; also used to remove it again. */
    private static final String REGISTRATION_KEY = "apus-telemetry-log-tail";

    // Matches BlueMap's own render-progress log line, produced by BlueMapCLI's internal
    // progress-logging TimerTask (decompiled and verified against bluemap-cli 5.23, see
    // runner/README.md#telemetry). Two examples actually observed against a real render:
    //   updating map 'overworld': 35.208% (ETA: 38 seconds)
    //   updating map 'overworld': 100.0%
    // Group 1: description (everything before ": "), group 2: percentage (always '.'
    // decimal-separated -- it comes from Java's locale-independent double-to-string
    // conversion, never the host locale), groups 3/4: optional ETA magnitude and unit.
    private static final Pattern PROGRESS_LINE =
            Pattern.compile("^(.*): (\\d+(?:\\.\\d+)?)%(?: \\(ETA: (\\d+(?:\\.\\d+)?) ([a-zA-Z]+)\\))?$");

    // Extracts the map id out of a description like "updating map 'overworld'". Applied to
    // group 1 of PROGRESS_LINE, not to the raw log line.
    private static final Pattern MAP_ID_IN_DESCRIPTION = Pattern.compile(".*map '([^']+)'.*");

    private final AtomicReference<TaskInfo> lastTask = new AtomicReference<>();

    /**
     * Registers this instance on {@code Logger.global} so it starts receiving BlueMap's log
     * lines. Safe to call multiple times; a second call replaces the first registration (via
     * BlueMap's own {@code MultiLogger.put}, which removes-then-adds under the same key).
     */
    public void register() {
        Logger.global.put(REGISTRATION_KEY, () -> this);
    }

    /** Unregisters this instance. Safe to call even if {@link #register()} was never called. */
    public void unregister() {
        Logger.global.remove(REGISTRATION_KEY);
    }

    @Override
    public void logInfo(String message) {
        Matcher lineMatch = PROGRESS_LINE.matcher(message);
        if (!lineMatch.matches()) {
            // Not a progress line (e.g. "Start updating 1 maps ..." or "Your maps are now
            // all up-to-date!") -- leave the last known state untouched, exactly like a
            // RenderManager that hasn't been asked yet would.
            return;
        }

        String description = lineMatch.group(1);
        double progress = Double.parseDouble(lineMatch.group(2)) / 100.0;

        long etaMillis = 0; // 0 means "unknown", matching RenderManager's own convention
        // (see RenderProgressProbe, which treats a non-positive eta as "no estimate").
        String etaMagnitude = lineMatch.group(3);
        String etaUnit = lineMatch.group(4);
        if (etaMagnitude != null && etaUnit != null) {
            long unitMillis = millisPerUnit(etaUnit);
            if (unitMillis > 0) {
                etaMillis = Math.round(Double.parseDouble(etaMagnitude) * unitMillis);
            }
        }

        String mapId = null;
        Matcher mapIdMatch = MAP_ID_IN_DESCRIPTION.matcher(description);
        if (mapIdMatch.matches()) {
            mapId = mapIdMatch.group(1);
        }

        lastTask.set(new TaskInfo(mapId, description, progress, etaMillis));
    }

    @Override
    public void logDebug(String message) {
        // Not needed: the progress line this class cares about is always logged at INFO.
    }

    @Override
    public void logWarning(String message) {
        // Nothing to react to; BlueMap keeps running the render regardless.
    }

    @Override
    public void logError(String message, Throwable throwable) {
        // Nothing to react to; a failed render still exits with a non-zero code the
        // container's own exit status already surfaces.
    }

    @Override
    public boolean isRunning() {
        // No log line tells us "idle" in render-only mode (BlueMap only logs that while
        // watching for file changes, i.e. under -u/--watch, which apus/runner never passes).
        // Best-effort reading: a render is (or was) running once at least one progress line
        // has been observed.
        return lastTask.get() != null;
    }

    @Override
    public int queuedTasks() {
        return -1; // unknown: not reported in any log line
    }

    @Override
    public int renderThreads() {
        return -1; // unknown: not reported in any log line
    }

    @Override
    public TaskInfo currentTask() {
        return lastTask.get();
    }

    private static long millisPerUnit(String unit) {
        return switch (unit.toLowerCase(java.util.Locale.ROOT)) {
            case "second", "seconds" -> 1_000L;
            case "minute", "minutes" -> 60_000L;
            case "hour", "hours" -> 3_600_000L;
            case "day", "days" -> 86_400_000L;
            default -> -1L; // unrecognized unit: treat the ETA as unknown rather than guessing
        };
    }
}
