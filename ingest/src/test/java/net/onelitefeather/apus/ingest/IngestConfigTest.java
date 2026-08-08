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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import net.onelitefeather.apus.ingest.connector.PterodactylConnector;
import net.onelitefeather.apus.ingest.connector.S3SourceConnector;
import org.junit.jupiter.api.Test;

class IngestConfigTest {

    private static Map<String, String> minimalS3Env() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(IngestConfig.ENV_SOURCE_TYPE, "s3");
        env.put(IngestConfig.ENV_WORLD_NAME, "world");
        env.put(IngestConfig.ENV_SOURCE_VERSION, "2026-08-01T00-00-00Z.zip");
        env.put(IngestConfig.ENV_BUNDLE_BUCKET, "bundles");
        env.put(IngestConfig.ENV_BUNDLE_TENANT, "acme");
        env.put(IngestConfig.ENV_BUNDLE_WORLD_ID, "spawn");
        env.put(IngestConfig.ENV_BUNDLE_VERSION, "v1");
        env.put(IngestConfig.ENV_S3_ENDPOINT, "http://minio:9000");
        env.put(IngestConfig.ENV_S3_ACCESS_KEY, "access");
        env.put(IngestConfig.ENV_S3_SECRET_KEY, "secret");
        env.put(IngestConfig.ENV_SOURCE_S3_BUCKET, "worlds");
        return env;
    }

    @Test
    void everyRequiredFieldPresentProducesAValidConfig() {
        IngestConfig config = IngestConfig.fromEnv(minimalS3Env());

        assertEquals("s3", config.sourceType());
        assertEquals("world", config.worldName());
        assertNull(config.forcedLayout(), "auto is the default and translates to 'let the detector decide'");
        assertEquals("acme", config.bundleTenant());
        assertEquals("spawn", config.bundleWorldId());
        assertEquals("v1", config.bundleVersion());
        assertEquals("us-east-1", config.s3Region(), "unset region falls back to the same default as runner");
        assertNull(config.minecraftVersion(), "not part of the minimal env, must stay null rather than guessed");
        assertEquals(Duration.ofSeconds(10), config.progressInterval());
    }

    @Test
    void anExplicitLayoutOtherThanAutoIsPassedThroughAsTheForcedLayout() {
        Map<String, String> env = minimalS3Env();
        env.put(IngestConfig.ENV_LAYOUT, "bukkit");

        IngestConfig config = IngestConfig.fromEnv(env);

        assertEquals("bukkit", config.forcedLayout());
    }

    @Test
    void missingSourceTypeAbortsBeforeAnythingElseIsChecked() {
        Map<String, String> env = minimalS3Env();
        env.remove(IngestConfig.ENV_SOURCE_TYPE);

        IngestConfig.ConfigurationException e =
                assertThrows(IngestConfig.ConfigurationException.class, () -> IngestConfig.fromEnv(env));
        assertTrue(e.getMessage().contains(IngestConfig.ENV_SOURCE_TYPE));
    }

    @Test
    void blankValuesAreTreatedAsMissing() {
        Map<String, String> env = minimalS3Env();
        env.put(IngestConfig.ENV_WORLD_NAME, "   ");

        assertThrows(IngestConfig.ConfigurationException.class, () -> IngestConfig.fromEnv(env));
    }

    @Test
    void anUnsupportedSourceTypeIsRejectedRatherThanGuessedAt() {
        Map<String, String> env = minimalS3Env();
        env.put(IngestConfig.ENV_SOURCE_TYPE, "upload");

        IngestConfig.ConfigurationException e =
                assertThrows(IngestConfig.ConfigurationException.class, () -> IngestConfig.fromEnv(env));
        assertTrue(e.getMessage().contains("upload"));
    }

    @Test
    void missingBundleDestinationFieldsAreDetectedEvenWhenSourceConfigIsComplete() {
        Map<String, String> env = minimalS3Env();
        env.remove(IngestConfig.ENV_BUNDLE_BUCKET);

        IngestConfig.ConfigurationException e =
                assertThrows(IngestConfig.ConfigurationException.class, () -> IngestConfig.fromEnv(env));
        assertTrue(e.getMessage().contains(IngestConfig.ENV_BUNDLE_BUCKET));
    }

    @Test
    void s3SourceConfigMapsEnvVarsToTheConnectorsOwnConfigKeys() {
        Map<String, String> env = minimalS3Env();
        env.put(IngestConfig.ENV_SOURCE_S3_ENDPOINT, "http://source-minio:9000");
        env.put(IngestConfig.ENV_SOURCE_S3_PREFIX, "backups/");
        env.put(IngestConfig.ENV_SOURCE_S3_ACCESS_KEY, "src-access");
        env.put(IngestConfig.ENV_SOURCE_S3_SECRET_KEY, "src-secret");
        env.put(IngestConfig.ENV_SOURCE_S3_REGION, "eu-central-1");

        Map<String, String> sourceConfig = IngestConfig.fromEnv(env).sourceConfig();

        assertEquals("worlds", sourceConfig.get(S3SourceConnector.CONFIG_BUCKET));
        assertEquals("http://source-minio:9000", sourceConfig.get(S3SourceConnector.CONFIG_ENDPOINT));
        assertEquals("backups/", sourceConfig.get(S3SourceConnector.CONFIG_PREFIX));
        assertEquals("src-access", sourceConfig.get(S3SourceConnector.CONFIG_ACCESS_KEY_ID));
        assertEquals("src-secret", sourceConfig.get(S3SourceConnector.CONFIG_SECRET_ACCESS_KEY));
        assertEquals("eu-central-1", sourceConfig.get(S3SourceConnector.CONFIG_REGION));
    }

    @Test
    void missingSourceSpecificFieldIsDetectedForPterodactylToo() {
        Map<String, String> env = minimalS3Env();
        env.put(IngestConfig.ENV_SOURCE_TYPE, "pterodactyl");
        env.put(IngestConfig.ENV_PTERODACTYL_PANEL_URL, "https://panel.example.com");
        env.put(IngestConfig.ENV_PTERODACTYL_SERVER_ID, "abc123");
        env.put(IngestConfig.ENV_PTERODACTYL_WORLD_PATHS, "world,world_nether,world_the_end");
        // APUS_PTERODACTYL_API_KEY intentionally left unset.

        IngestConfig.ConfigurationException e =
                assertThrows(IngestConfig.ConfigurationException.class, () -> IngestConfig.fromEnv(env));
        assertTrue(e.getMessage().contains(IngestConfig.ENV_PTERODACTYL_API_KEY));
    }

    @Test
    void pterodactylSourceConfigMapsEnvVarsToTheConnectorsOwnConfigKeys() {
        Map<String, String> env = minimalS3Env();
        env.put(IngestConfig.ENV_SOURCE_TYPE, "pterodactyl");
        env.put(IngestConfig.ENV_PTERODACTYL_PANEL_URL, "https://panel.example.com");
        env.put(IngestConfig.ENV_PTERODACTYL_SERVER_ID, "abc123");
        env.put(IngestConfig.ENV_PTERODACTYL_API_KEY, "ptlc_secret");
        env.put(IngestConfig.ENV_PTERODACTYL_WORLD_PATHS, "world,world_nether,world_the_end");

        Map<String, String> sourceConfig = IngestConfig.fromEnv(env).sourceConfig();

        assertEquals("https://panel.example.com", sourceConfig.get(PterodactylConnector.CONFIG_PANEL_URL));
        assertEquals("abc123", sourceConfig.get(PterodactylConnector.CONFIG_SERVER_ID));
        assertEquals("ptlc_secret", sourceConfig.get(PterodactylConnector.CONFIG_API_KEY));
        assertEquals("world,world_nether,world_the_end", sourceConfig.get(PterodactylConnector.CONFIG_WORLD_PATHS));
    }

    @Test
    void minecraftVersionIsPassedThroughWhenSupplied() {
        Map<String, String> env = minimalS3Env();
        env.put(IngestConfig.ENV_MC_VERSION, "1.21.10");

        assertEquals("1.21.10", IngestConfig.fromEnv(env).minecraftVersion());
    }

    @Test
    void progressIntervalMustBeAPositiveInteger() {
        Map<String, String> env = minimalS3Env();
        env.put(IngestConfig.ENV_PROGRESS_INTERVAL_SECONDS, "not-a-number");

        assertThrows(IngestConfig.ConfigurationException.class, () -> IngestConfig.fromEnv(env));

        env.put(IngestConfig.ENV_PROGRESS_INTERVAL_SECONDS, "0");
        assertThrows(IngestConfig.ConfigurationException.class, () -> IngestConfig.fromEnv(env));

        env.put(IngestConfig.ENV_PROGRESS_INTERVAL_SECONDS, "30");
        assertEquals(Duration.ofSeconds(30), IngestConfig.fromEnv(env).progressInterval());
    }
}
