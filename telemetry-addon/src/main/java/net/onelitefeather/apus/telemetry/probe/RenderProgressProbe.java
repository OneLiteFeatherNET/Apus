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

import java.util.function.Supplier;
import net.onelitefeather.apus.telemetry.ProgressSnapshot;

/**
 * Turns BlueMap's render state into a {@link ProgressSnapshot}.
 *
 * <p>This class never throws. Progress reporting is a convenience: if BlueMap's internals
 * move under us, the render must still run and the snapshot simply degrades.
 */
public final class RenderProgressProbe {

    private final Supplier<RenderManagerAccess> accessSupplier;

    public RenderProgressProbe(Supplier<RenderManagerAccess> accessSupplier) {
        this.accessSupplier = accessSupplier;
    }

    public ProgressSnapshot sample() {
        RenderManagerAccess access;
        try {
            access = accessSupplier.get();
        } catch (Throwable t) {
            return ProgressSnapshot.unknown(describe(t));
        }

        if (access == null) {
            // The BlueMap API has not fired onEnable yet. Normal during startup.
            return new ProgressSnapshot(
                    ProgressSnapshot.State.STARTING, null, -1.0, -1L, -1, -1, false, "waiting for BlueMap API");
        }

        try {
            int queued = access.queuedTasks();
            int threads = access.renderThreads();
            RenderManagerAccess.TaskInfo task = access.currentTask();

            if (task == null) {
                return ProgressSnapshot.idle(queued, threads);
            }

            // BlueMap returns 0 from estimateCurrentRenderTaskTimeRemaining() when it has
            // no basis for an estimate; that is "unknown", not "finishing right now".
            long etaSeconds = task.etaMillis() > 0 ? task.etaMillis() / 1000L : -1L;

            return new ProgressSnapshot(
                    ProgressSnapshot.State.RENDERING,
                    task.mapId(),
                    task.progress(),
                    etaSeconds,
                    queued,
                    threads,
                    false,
                    task.description());
        } catch (Throwable t) {
            return ProgressSnapshot.unknown(describe(t));
        }
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null ? t.getClass().getSimpleName() : t.getClass().getSimpleName() + ": " + message;
    }
}
