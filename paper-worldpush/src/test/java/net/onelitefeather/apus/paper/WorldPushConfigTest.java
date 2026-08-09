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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldPushConfigTest {

    @Test
    void validConfigParsesEveryField() {
        WorldPushConfig config = WorldPushConfig.from(source(fullConfig()));

        assertEquals("world", config.worldName());
        assertEquals("acme", config.tenant());
        assertEquals("survival-source", config.sourceName());
        assertEquals("secret-token", config.pushToken());
        assertEquals("apus-worldpush-staging", config.stagingDirectory());
        assertEquals("https://s3.example.org", config.s3Endpoint());
        assertEquals("apus-worlds", config.s3Bucket());
        assertEquals("us-east-1", config.s3Region());
        assertEquals("access-key", config.s3AccessKey());
        assertEquals("secret-key", config.s3SecretKey());
        assertEquals("staging/", config.s3StagingPrefix());
        assertEquals("https://apus.example.org", config.apusApiBaseUrl().toString());
        assertEquals(30, config.intervalMinutes());
    }

    @Test
    void stagingPrefixIsNormalisedToEndWithASlash() {
        Map<String, String> values = fullConfig();
        values.put("s3.staging-prefix", "staging/acme");

        WorldPushConfig config = WorldPushConfig.from(source(values));

        assertEquals("staging/acme/", config.s3StagingPrefix());
    }

    @Test
    void missingWorldNameFailsFast() {
        Map<String, String> values = fullConfig();
        values.remove("world-name");

        WorldPushConfig.ConfigurationException e =
                assertThrows(WorldPushConfig.ConfigurationException.class, () -> WorldPushConfig.from(source(values)));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("world-name"));
    }

    @Test
    void missingPushTokenFailsFast() {
        Map<String, String> values = fullConfig();
        values.remove("push-token");

        assertThrows(WorldPushConfig.ConfigurationException.class, () -> WorldPushConfig.from(source(values)));
    }

    @Test
    void missingSourceNameFailsFast() {
        Map<String, String> values = fullConfig();
        values.remove("world-source-name");

        WorldPushConfig.ConfigurationException e =
                assertThrows(WorldPushConfig.ConfigurationException.class, () -> WorldPushConfig.from(source(values)));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("world-source-name"));
    }

    @Test
    void blankS3CredentialsFailFast() {
        Map<String, String> values = fullConfig();
        values.put("s3.access-key", "  ");

        assertThrows(WorldPushConfig.ConfigurationException.class, () -> WorldPushConfig.from(source(values)));
    }

    @Test
    void malformedApiBaseUrlFailsFast() {
        Map<String, String> values = fullConfig();
        values.put("apus.api-base-url", "not a url");

        assertThrows(WorldPushConfig.ConfigurationException.class, () -> WorldPushConfig.from(source(values)));
    }

    @Test
    void relativeApiBaseUrlFailsFast() {
        Map<String, String> values = fullConfig();
        values.put("apus.api-base-url", "/api");

        assertThrows(WorldPushConfig.ConfigurationException.class, () -> WorldPushConfig.from(source(values)));
    }

    @Test
    void zeroOrNegativeIntervalFailsFast() {
        WorldPushConfig.ConfigurationException e = assertThrows(
                WorldPushConfig.ConfigurationException.class,
                () -> WorldPushConfig.from(new ConfigSource() {
                    @Override
                    public String getString(String path) {
                        return fullConfig().get(path);
                    }

                    @Override
                    public long getLong(String path, long defaultValue) {
                        return "schedule.interval-minutes".equals(path) ? 0 : defaultValue;
                    }
                }));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("interval-minutes"));
    }

    @Test
    void defaultsApplyWhenOptionalKeysAreAbsent() {
        Map<String, String> values = fullConfig();
        values.remove("staging-directory");
        values.remove("s3.region");
        values.remove("s3.staging-prefix");

        WorldPushConfig config = WorldPushConfig.from(source(values));

        assertEquals("apus-worldpush-staging", config.stagingDirectory());
        assertEquals("us-east-1", config.s3Region());
        assertEquals("staging/", config.s3StagingPrefix());
        assertEquals(30, config.intervalMinutes());
    }

    private static Map<String, String> fullConfig() {
        Map<String, String> values = new HashMap<>();
        values.put("world-name", "world");
        values.put("tenant", "acme");
        values.put("world-source-name", "survival-source");
        values.put("push-token", "secret-token");
        values.put("staging-directory", "apus-worldpush-staging");
        values.put("s3.endpoint", "https://s3.example.org");
        values.put("s3.bucket", "apus-worlds");
        values.put("s3.region", "us-east-1");
        values.put("s3.access-key", "access-key");
        values.put("s3.secret-key", "secret-key");
        values.put("s3.staging-prefix", "staging/");
        values.put("apus.api-base-url", "https://apus.example.org");
        return values;
    }

    private static ConfigSource source(Map<String, String> values) {
        return new ConfigSource() {
            @Override
            public String getString(String path) {
                return values.get(path);
            }

            @Override
            public long getLong(String path, long defaultValue) {
                return "schedule.interval-minutes".equals(path) ? 30 : defaultValue;
            }
        };
    }
}
