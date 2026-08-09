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

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Entry point of the Apus world-push plugin: periodically takes a consistent, incremental copy
 * of this server's configured world and uploads it to a staging prefix in S3, then reports the
 * new version to the Apus API. See the design spec, §3 ("Push: async + inkrementell in
 * Staging-Prefix") and §6.4.
 *
 * <p>Every push cycle runs on Paper's {@code AsyncScheduler} -- never the main thread, never
 * {@code BukkitScheduler#runTaskAsynchronously} (Paper's own guidance prefers the newer
 * schedulers, see {@code docs.papermc.io/paper/dev/folia-support}). The only main-thread work is
 * the brief autosave-pause-and-force-save step inside {@link BukkitSaveCoordinator}, bridged back
 * onto the main thread per call. A cycle still running when the next one would start is skipped
 * rather than queued or run concurrently -- {@link #cycleRunning} enforces that.
 */
public final class WorldPushPlugin extends JavaPlugin {

    private final AtomicBoolean cycleRunning = new AtomicBoolean(false);

    private WorldPushConfig config;
    private S3WorldUploader uploader;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadConfig();

        try {
            config = WorldPushConfig.from(new BukkitConfigSource(getConfig()));
        } catch (WorldPushConfig.ConfigurationException e) {
            getSLF4JLogger().error("Invalid config.yml, disabling: {}", e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        uploader = S3WorldUploader.create(config);

        Path serverRoot = getServer().getWorldContainer().toPath();
        Path stagingRoot = getDataFolder().toPath().resolve(config.stagingDirectory());
        Path stateFile = getDataFolder().toPath().resolve("push-state.properties");

        SaveCoordinator saveCoordinator =
                new BukkitSaveCoordinator(this, () -> getServer().getWorld(config.worldName()));
        PushNotifier notifier = new HttpPushNotifier(config.apusApiBaseUrl(), config.pushToken());
        PushCycleRunner runner = new PushCycleRunner(
                new IncrementalWorldCopier(), saveCoordinator, uploader, notifier, serverRoot, stagingRoot, stateFile,
                config);

        getServer()
                .getAsyncScheduler()
                .runAtFixedRate(
                        this,
                        task -> runCycleGuarded(runner),
                        config.intervalMinutes(),
                        config.intervalMinutes(),
                        TimeUnit.MINUTES);

        getSLF4JLogger()
                .info(
                        "Apus world push enabled for world '{}', tenant '{}', source '{}', every {} minute(s).",
                        config.worldName(),
                        config.tenant(),
                        config.sourceName(),
                        config.intervalMinutes());
    }

    @Override
    public void onDisable() {
        getServer().getAsyncScheduler().cancelTasks(this);
        getServer().getGlobalRegionScheduler().cancelTasks(this);
        if (uploader != null) {
            uploader.close();
        }
    }

    private void runCycleGuarded(PushCycleRunner runner) {
        if (!cycleRunning.compareAndSet(false, true)) {
            getSLF4JLogger().debug("Skipping this push cycle: the previous one is still running.");
            return;
        }
        try {
            runner.runCycle();
        } catch (IOException e) {
            getSLF4JLogger().warn("Push cycle failed: {}", e.getMessage(), e);
        } catch (RuntimeException e) {
            // Covers HttpPushNotifier.PushNotificationException and any unexpected failure from
            // the uploader/save coordinator -- a bad cycle must never crash the scheduler and
            // silently stop all future pushes.
            getSLF4JLogger().warn("Push cycle failed: {}", e.getMessage(), e);
        } finally {
            cycleRunning.set(false);
        }
    }
}
