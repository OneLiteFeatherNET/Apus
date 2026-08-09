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
import java.util.List;
import java.util.logging.Logger;

/**
 * Runs one complete push cycle: quiesce autosave, force a save, copy changed region files,
 * upload them, and report completion -- steps 1 through 3 of the design brief, in order.
 *
 * <p><b>Threading contract.</b> This class itself does not touch any thread -- it just calls
 * {@link SaveCoordinator}, {@link IncrementalWorldCopier}, {@link WorldUploader} and {@link
 * PushNotifier} in sequence. The threading guarantee the design brief asks for ("nothing may
 * block the server thread") comes entirely from which thread {@link #runCycle()} itself is
 * called on and which {@link SaveCoordinator} implementation it is given: {@link
 * WorldPushPlugin} only ever calls this from Paper's {@code AsyncScheduler}, and {@link
 * BukkitSaveCoordinator} bridges only its own three method calls onto the main thread, blocking
 * this (async) thread while it waits -- never the other way around.
 *
 * <p><b>State progression.</b> {@link CopyState} is only persisted to disk once the entire
 * cycle -- copy, upload, and notify -- has succeeded. If anything after the copy step fails
 * (an upload, the notification), the in-memory state mutations {@link IncrementalWorldCopier}
 * made are simply discarded; the next cycle reloads the last good state from disk and will
 * see the same files as changed again. See {@link CopyState} and {@link IncrementalWorldCopier}
 * for the matching per-file crash-safety guarantee this builds on.
 */
public final class PushCycleRunner {

    private static final Logger LOGGER = Logger.getLogger(PushCycleRunner.class.getName());

    private final IncrementalWorldCopier copier;
    private final SaveCoordinator saveCoordinator;
    private final WorldUploader uploader;
    private final PushNotifier notifier;
    private final Path serverRoot;
    private final Path stagingRoot;
    private final Path stateFile;
    private final WorldPushConfig config;

    public PushCycleRunner(
            IncrementalWorldCopier copier,
            SaveCoordinator saveCoordinator,
            WorldUploader uploader,
            PushNotifier notifier,
            Path serverRoot,
            Path stagingRoot,
            Path stateFile,
            WorldPushConfig config) {
        this.copier = copier;
        this.saveCoordinator = saveCoordinator;
        this.uploader = uploader;
        this.notifier = notifier;
        this.serverRoot = serverRoot;
        this.stagingRoot = stagingRoot;
        this.stateFile = stateFile;
        this.config = config;
    }

    /**
     * Runs one push cycle to completion. Must be called off the main thread -- see the class
     * Javadoc.
     *
     * @throws IOException if reading region directories or copying/persisting state fails
     * @throws HttpPushNotifier.PushNotificationException if the completion report is rejected or
     *     unreachable (only thrown by the real {@link PushNotifier}; a test fake may throw
     *     whatever it likes)
     */
    public void runCycle() throws IOException {
        saveCoordinator.disableAutoSave();
        try {
            saveCoordinator.forceSave();
        } finally {
            // Always re-enabled, even if forceSave() failed -- a server permanently stuck
            // without autosave because one push cycle had a bad day is a worse outcome than
            // that cycle's copy being skipped or stale.
            saveCoordinator.enableAutoSave();
        }

        CopyState state = CopyState.load(stateFile);
        List<DimensionRegionDir> regionDirs = DimensionLayout.forWorld(serverRoot, config.worldName());
        CopyResult result = copier.copyChanged(regionDirs, stagingRoot, state);

        if (result.isEmpty()) {
            // Still persisted: copyChanged() may have refreshed size/mtime for files whose
            // content did not actually change (see IncrementalWorldCopier), and there is
            // nothing risky about saving that -- no upload or notification happened.
            state.save(stateFile);
            LOGGER.fine("Push cycle: no region files changed, nothing to upload.");
            return;
        }

        for (String relativePath : result.copiedRelativePaths()) {
            uploader.upload(stagingRoot.resolve(relativePath), config.s3StagingPrefix() + relativePath);
        }

        notifier.notifyPushComplete(new PushSummary(
                config.tenant(), config.worldName(), result.copiedRelativePaths().size(), result.copiedBytes()));

        state.save(stateFile);
        LOGGER.info("Push cycle: uploaded " + result.copiedRelativePaths().size() + " region file(s), "
                + result.copiedBytes() + " bytes.");
    }
}
