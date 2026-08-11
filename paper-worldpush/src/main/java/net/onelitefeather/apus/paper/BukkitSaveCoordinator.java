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
package net.onelitefeather.apus.paper;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/**
 * {@link SaveCoordinator} backed by the real Bukkit/Paper world. Bridges from the calling thread
 * (expected to be an async worker, never the main thread -- see below) onto Paper's {@code
 * GlobalRegionScheduler} for each step, and blocks until that main-thread step has actually run,
 * so callers can treat this as an ordinary synchronous dependency.
 *
 * <p><b>Must never be called from the main thread.</b> Every method here submits work to the
 * global region and then blocks waiting for it; calling this from the main thread would deadlock
 * (the thread would be waiting for itself to become free). {@link WorldPushPlugin} only ever
 * invokes {@link PushCycleRunner} from Paper's {@code AsyncScheduler}, never from a Bukkit
 * event handler or a task scheduled on the main/region scheduler.
 *
 * <p>The world is resolved fresh on every call via {@code worldSupplier} rather than cached once,
 * since a world can in principle be unloaded and reloaded while the plugin is running; if it is
 * not currently loaded, each step is a silent no-op rather than an error -- there is nothing
 * meaningful to save.
 *
 * <p><b>Untested.</b> This class has no automated test coverage: exercising it needs a running
 * Paper server (to observe a real world actually pause/resume autosave and be forced to disk),
 * which is out of reach for this module's test suite -- see the phase 6 task report.
 */
public final class BukkitSaveCoordinator implements SaveCoordinator {

    private static final Logger LOGGER = Logger.getLogger(BukkitSaveCoordinator.class.getName());
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 30;

    private final Plugin plugin;
    private final Supplier<World> worldSupplier;

    public BukkitSaveCoordinator(Plugin plugin, Supplier<World> worldSupplier) {
        this.plugin = plugin;
        this.worldSupplier = worldSupplier;
    }

    @Override
    public void disableAutoSave() {
        runOnMainThreadAndAwait(world -> world.setAutoSave(false));
    }

    @Override
    public void forceSave() {
        runOnMainThreadAndAwait(World::save);
    }

    @Override
    public void enableAutoSave() {
        runOnMainThreadAndAwait(world -> world.setAutoSave(true));
    }

    private void runOnMainThreadAndAwait(Consumer<World> action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        plugin.getServer().getGlobalRegionScheduler().run(plugin, task -> {
            try {
                World world = worldSupplier.get();
                if (world == null) {
                    LOGGER.warning("World is not currently loaded; skipping this save step.");
                } else {
                    action.accept(world);
                }
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            future.get(MAIN_THREAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for a main-thread world save step.", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Main-thread world save step failed.", e.getCause());
        } catch (TimeoutException e) {
            LOGGER.log(Level.SEVERE, "Main-thread world save step did not complete within "
                    + MAIN_THREAD_TIMEOUT_SECONDS + "s.", e);
            throw new IllegalStateException("Main-thread world save step timed out.", e);
        }
    }
}
