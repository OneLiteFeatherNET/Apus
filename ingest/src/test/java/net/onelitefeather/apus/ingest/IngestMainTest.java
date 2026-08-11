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
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the one part of {@link IngestMain#run} that is meaningfully testable without a real
 * source and a real S3 endpoint: that a missing or invalid required variable aborts cleanly,
 * with a non-zero exit code, before any directory is even created -- the "no half-started job"
 * requirement from the phase 2b plan. The full happy path (fetch -> detect -> write), plus the
 * proof that the resulting bundle is exactly what the {@code runner} render container expects to
 * read, is exercised end to end against MinIO and the real {@code apus/runner} image in {@code
 * runner}'s {@code :runner:integrationTest} task ({@code IngestRenderContractTest}) -- not here,
 * since proving the ingest/render contract needs both modules together.
 */
class IngestMainTest {

    @Test
    void missingRequiredVariableExitsNonZeroAndTouchesNothing(@TempDir Path tempDir) {
        Path workDir = tempDir.resolve("source");
        Map<String, String> env = new LinkedHashMap<>(); // completely empty

        int exitCode = IngestMain.run(env, workDir);

        assertEquals(IngestMain.EXIT_CONFIGURATION_ERROR, exitCode);
        assertFalse(Files.exists(workDir), "nothing may be fetched before configuration is fully valid");
    }

    @Test
    void unsupportedSourceTypeExitsNonZeroAndTouchesNothing(@TempDir Path tempDir) {
        Path workDir = tempDir.resolve("source");
        Map<String, String> env = new LinkedHashMap<>();
        env.put(IngestConfig.ENV_SOURCE_TYPE, "ftp");
        env.put(IngestConfig.ENV_WORLD_NAME, "world");
        env.put(IngestConfig.ENV_SOURCE_VERSION, "v1");
        env.put(IngestConfig.ENV_BUNDLE_BUCKET, "bundles");
        env.put(IngestConfig.ENV_BUNDLE_TENANT, "acme");
        env.put(IngestConfig.ENV_BUNDLE_WORLD_ID, "spawn");
        env.put(IngestConfig.ENV_BUNDLE_VERSION, "v1");
        env.put(IngestConfig.ENV_S3_ENDPOINT, "http://minio:9000");
        env.put(IngestConfig.ENV_S3_ACCESS_KEY, "access");
        env.put(IngestConfig.ENV_S3_SECRET_KEY, "secret");

        int exitCode = IngestMain.run(env, workDir);

        assertEquals(IngestMain.EXIT_CONFIGURATION_ERROR, exitCode);
        assertFalse(Files.exists(workDir));
    }
}
