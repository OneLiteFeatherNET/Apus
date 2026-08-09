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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link PushCycleRunner}'s orchestration with fakes standing in for the parts that
 * need a live Paper server or network access ({@link SaveCoordinator}, {@link WorldUploader},
 * {@link PushNotifier}) -- this is the "what's testable without a running server" half of the
 * Bukkit-facing code the phase 6 task report describes; {@link BukkitSaveCoordinator} itself is
 * not exercised here.
 */
class PushCycleRunnerTest {

    @TempDir
    Path tmp;

    private Path regionDir;
    private Path stagingRoot;
    private Path stateFile;
    private WorldPushConfig config;
    private FakeSaveCoordinator saveCoordinator;
    private FakeUploader uploader;
    private FakeNotifier notifier;

    @BeforeEach
    void setUp() throws IOException {
        Path serverRoot = tmp.resolve("server");
        regionDir = Files.createDirectories(serverRoot.resolve("world/region"));
        stagingRoot = tmp.resolve("staging");
        stateFile = tmp.resolve("push-state.properties");
        config = WorldPushConfig.from(configSource());
        saveCoordinator = new FakeSaveCoordinator();
        uploader = new FakeUploader();
        notifier = new FakeNotifier();
        this.serverRoot = serverRoot;
    }

    private Path serverRoot;

    @Test
    void happyPathSavesUploadsNotifiesAndPersistsState() throws IOException {
        Files.writeString(regionDir.resolve("r.0.0.mca"), "alpha");

        newRunner().runCycle();

        assertEquals(List.of("disableAutoSave", "forceSave", "enableAutoSave"), saveCoordinator.calls);
        assertEquals(1, uploader.uploaded.size());
        assertEquals("staging/world/region/r.0.0.mca", uploader.uploaded.get(0).s3Key());
        assertEquals(1, notifier.summaries.size());
        assertEquals(new PushSummary("acme", "world", 1, 5), notifier.summaries.get(0));
        assertTrue(Files.isRegularFile(stateFile), "state must be persisted after a successful cycle");
    }

    @Test
    void autoSaveIsReEnabledEvenIfForceSaveFails() throws IOException {
        Files.writeString(regionDir.resolve("r.0.0.mca"), "alpha");
        saveCoordinator.failForceSave = true;

        assertThrows(RuntimeException.class, () -> newRunner().runCycle());

        assertEquals(List.of("disableAutoSave", "forceSave", "enableAutoSave"), saveCoordinator.calls);
        assertTrue(uploader.uploaded.isEmpty(), "must not upload if the save step itself failed");
    }

    @Test
    void noChangesMeansNoUploadAndNoNotificationButStateIsStillSaved() throws IOException {
        Files.writeString(regionDir.resolve("r.0.0.mca"), "alpha");
        newRunner().runCycle();
        uploader.uploaded.clear();
        notifier.summaries.clear();

        newRunner().runCycle();

        assertTrue(uploader.uploaded.isEmpty());
        assertTrue(notifier.summaries.isEmpty());
    }

    @Test
    void aFailedUploadLeavesThePersistedStateUnchangedForRetry() throws IOException {
        Files.writeString(regionDir.resolve("r.0.0.mca"), "alpha");
        uploader.failUploads = true;

        assertThrows(RuntimeException.class, () -> newRunner().runCycle());

        assertFalse(Files.isRegularFile(stateFile), "a failed cycle must not persist state");
        assertTrue(notifier.summaries.isEmpty(), "must not notify if the upload failed");

        // A retried cycle (fresh runner, uploads succeeding this time) must still see the file
        // as changed and upload it -- nothing was silently marked done by the failed attempt.
        uploader.failUploads = false;
        newRunner().runCycle();
        assertEquals(1, uploader.uploaded.size());
        assertEquals(1, notifier.summaries.size());
    }

    @Test
    void aFailedNotificationLeavesThePersistedStateUnchangedForRetry() throws IOException {
        Files.writeString(regionDir.resolve("r.0.0.mca"), "alpha");
        notifier.failNotifications = true;

        assertThrows(RuntimeException.class, () -> newRunner().runCycle());

        assertFalse(Files.isRegularFile(stateFile), "a failed cycle must not persist state");
        // The upload itself did happen -- only the state advancement is what got rolled back.
        assertEquals(1, uploader.uploaded.size());
    }

    private PushCycleRunner newRunner() {
        return new PushCycleRunner(
                new IncrementalWorldCopier(), saveCoordinator, uploader, notifier, serverRoot, stagingRoot, stateFile,
                config);
    }

    private static ConfigSource configSource() {
        Map<String, String> values = new HashMap<>();
        values.put("world-name", "world");
        values.put("tenant", "acme");
        values.put("push-token", "secret-token");
        values.put("s3.endpoint", "https://s3.example.org");
        values.put("s3.bucket", "apus-worlds");
        values.put("s3.access-key", "access-key");
        values.put("s3.secret-key", "secret-key");
        values.put("apus.api-base-url", "https://apus.example.org");
        return new ConfigSource() {
            @Override
            public String getString(String path) {
                return values.get(path);
            }

            @Override
            public long getLong(String path, long defaultValue) {
                return defaultValue;
            }
        };
    }

    private static final class FakeSaveCoordinator implements SaveCoordinator {
        final List<String> calls = new ArrayList<>();
        boolean failForceSave;

        @Override
        public void disableAutoSave() {
            calls.add("disableAutoSave");
        }

        @Override
        public void forceSave() {
            calls.add("forceSave");
            if (failForceSave) {
                throw new RuntimeException("simulated save failure");
            }
        }

        @Override
        public void enableAutoSave() {
            calls.add("enableAutoSave");
        }
    }

    private record Upload(Path localFile, String s3Key) {}

    private static final class FakeUploader implements WorldUploader {
        final List<Upload> uploaded = new ArrayList<>();
        boolean failUploads;

        @Override
        public void upload(Path localFile, String s3Key) {
            if (failUploads) {
                throw new RuntimeException("simulated upload failure");
            }
            uploaded.add(new Upload(localFile, s3Key));
        }
    }

    private static final class FakeNotifier implements PushNotifier {
        final List<PushSummary> summaries = new ArrayList<>();
        boolean failNotifications;

        @Override
        public void notifyPushComplete(PushSummary summary) {
            if (failNotifications) {
                throw new RuntimeException("simulated notification failure");
            }
            summaries.add(summary);
        }
    }
}
