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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.onelitefeather.apus.ingest.connector.PterodactylConnector;
import net.onelitefeather.apus.ingest.connector.S3SourceConnector;

/**
 * The ingest job's complete configuration, read from environment variables and validated eagerly.
 *
 * <p>{@link #fromEnv(Map)} is the single place every required variable is checked. It either
 * returns a fully valid configuration or throws {@link ConfigurationException} before any network
 * call or filesystem write happens -- the job must never partially start (e.g. begin downloading
 * from the source) only to fail later because the destination bucket was never configured.
 *
 * <p>This is the environment-variable contract {@code IngestJobBuilder} (Task 6) builds Kubernetes
 * Jobs against, the same relationship {@code runner/README.md} documents between the render
 * container and its operator-built Job.
 */
public final class IngestConfig {

    // -- General --
    public static final String ENV_SOURCE_TYPE = "APUS_SOURCE_TYPE";
    public static final String ENV_WORLD_NAME = "APUS_WORLD_NAME";
    public static final String ENV_LAYOUT = "APUS_LAYOUT";
    public static final String ENV_SOURCE_VERSION = "APUS_SOURCE_VERSION";

    // -- Bundle destination --
    public static final String ENV_BUNDLE_BUCKET = "APUS_BUNDLE_BUCKET";
    public static final String ENV_BUNDLE_TENANT = "APUS_BUNDLE_TENANT";
    public static final String ENV_BUNDLE_WORLD_ID = "APUS_BUNDLE_WORLD_ID";
    public static final String ENV_BUNDLE_VERSION = "APUS_BUNDLE_VERSION";
    public static final String ENV_S3_ENDPOINT = "APUS_S3_ENDPOINT";
    public static final String ENV_S3_ACCESS_KEY = "APUS_S3_ACCESS_KEY";
    public static final String ENV_S3_SECRET_KEY = "APUS_S3_SECRET_KEY";
    public static final String ENV_S3_REGION = "APUS_S3_REGION";

    // -- Not part of the original brief contract, added because the manifest cannot be complete
    // without them; see ingest/README.md and task-5-report.md for why each one exists. --
    public static final String ENV_MC_VERSION = "APUS_MC_VERSION";
    public static final String ENV_PROGRESS_INTERVAL_SECONDS = "APUS_PROGRESS_INTERVAL_SECONDS";

    // -- Source-specific: s3 --
    public static final String ENV_SOURCE_S3_ENDPOINT = "APUS_SOURCE_S3_ENDPOINT";
    public static final String ENV_SOURCE_S3_BUCKET = "APUS_SOURCE_S3_BUCKET";
    public static final String ENV_SOURCE_S3_PREFIX = "APUS_SOURCE_S3_PREFIX";
    public static final String ENV_SOURCE_S3_ACCESS_KEY = "APUS_SOURCE_S3_ACCESS_KEY";
    public static final String ENV_SOURCE_S3_SECRET_KEY = "APUS_SOURCE_S3_SECRET_KEY";
    public static final String ENV_SOURCE_S3_REGION = "APUS_SOURCE_S3_REGION";

    // -- Source-specific: pterodactyl --
    public static final String ENV_PTERODACTYL_PANEL_URL = "APUS_PTERODACTYL_PANEL_URL";
    public static final String ENV_PTERODACTYL_SERVER_ID = "APUS_PTERODACTYL_SERVER_ID";
    public static final String ENV_PTERODACTYL_API_KEY = "APUS_PTERODACTYL_API_KEY";
    public static final String ENV_PTERODACTYL_WORLD_PATHS = "APUS_PTERODACTYL_WORLD_PATHS";

    private static final String TYPE_S3 = "s3";
    private static final String TYPE_PTERODACTYL = "pterodactyl";
    private static final Set<String> SUPPORTED_SOURCE_TYPES = Set.of(TYPE_S3, TYPE_PTERODACTYL);

    private static final String AUTO_LAYOUT = "auto";
    private static final String DEFAULT_S3_REGION = "us-east-1";
    private static final long DEFAULT_PROGRESS_INTERVAL_SECONDS = 10;

    private final String sourceType;
    private final String worldName;
    private final String forcedLayout;
    private final String sourceVersionId;
    private final String bundleBucket;
    private final String bundleTenant;
    private final String bundleWorldId;
    private final String bundleVersion;
    private final String s3Endpoint;
    private final String s3AccessKey;
    private final String s3SecretKey;
    private final String s3Region;
    private final String minecraftVersion;
    private final Duration progressInterval;
    private final Map<String, String> sourceConfig;

    private IngestConfig(
            String sourceType,
            String worldName,
            String forcedLayout,
            String sourceVersionId,
            String bundleBucket,
            String bundleTenant,
            String bundleWorldId,
            String bundleVersion,
            String s3Endpoint,
            String s3AccessKey,
            String s3SecretKey,
            String s3Region,
            String minecraftVersion,
            Duration progressInterval,
            Map<String, String> sourceConfig) {
        this.sourceType = sourceType;
        this.worldName = worldName;
        this.forcedLayout = forcedLayout;
        this.sourceVersionId = sourceVersionId;
        this.bundleBucket = bundleBucket;
        this.bundleTenant = bundleTenant;
        this.bundleWorldId = bundleWorldId;
        this.bundleVersion = bundleVersion;
        this.s3Endpoint = s3Endpoint;
        this.s3AccessKey = s3AccessKey;
        this.s3SecretKey = s3SecretKey;
        this.s3Region = s3Region;
        this.minecraftVersion = minecraftVersion;
        this.progressInterval = progressInterval;
        this.sourceConfig = sourceConfig;
    }

    /**
     * Reads and validates every configuration value the ingest job needs from {@code env}.
     *
     * @throws ConfigurationException if a required variable is missing/blank, or {@code
     *     APUS_SOURCE_TYPE} names a source this image does not implement
     */
    public static IngestConfig fromEnv(Map<String, String> env) {
        String sourceType = requireNonBlank(env, ENV_SOURCE_TYPE);
        if (!SUPPORTED_SOURCE_TYPES.contains(sourceType)) {
            throw new ConfigurationException("Unsupported " + ENV_SOURCE_TYPE + " '" + sourceType
                    + "': this image implements only " + SUPPORTED_SOURCE_TYPES
                    + ". The push sources ('upload', 'push') have no connector yet -- see the phase 2b plan.");
        }

        String worldName = requireNonBlank(env, ENV_WORLD_NAME);
        String layout = env.getOrDefault(ENV_LAYOUT, AUTO_LAYOUT);
        String forcedLayout = AUTO_LAYOUT.equals(layout) ? null : layout;
        String sourceVersionId = requireNonBlank(env, ENV_SOURCE_VERSION);

        String bundleBucket = requireNonBlank(env, ENV_BUNDLE_BUCKET);
        String bundleTenant = requireNonBlank(env, ENV_BUNDLE_TENANT);
        String bundleWorldId = requireNonBlank(env, ENV_BUNDLE_WORLD_ID);
        String bundleVersion = requireNonBlank(env, ENV_BUNDLE_VERSION);

        String s3Endpoint = requireNonBlank(env, ENV_S3_ENDPOINT);
        String s3AccessKey = requireNonBlank(env, ENV_S3_ACCESS_KEY);
        String s3SecretKey = requireNonBlank(env, ENV_S3_SECRET_KEY);
        String s3Region = env.getOrDefault(ENV_S3_REGION, DEFAULT_S3_REGION);

        String minecraftVersion = blankToNull(env.get(ENV_MC_VERSION));
        Duration progressInterval = Duration.ofSeconds(
                parsePositiveLong(env, ENV_PROGRESS_INTERVAL_SECONDS, DEFAULT_PROGRESS_INTERVAL_SECONDS));

        Map<String, String> sourceConfig =
                switch (sourceType) {
                    case TYPE_S3 -> s3SourceConfig(env);
                    case TYPE_PTERODACTYL -> pterodactylSourceConfig(env);
                    default -> throw new IllegalStateException("unreachable: " + sourceType);
                };

        return new IngestConfig(
                sourceType,
                worldName,
                forcedLayout,
                sourceVersionId,
                bundleBucket,
                bundleTenant,
                bundleWorldId,
                bundleVersion,
                s3Endpoint,
                s3AccessKey,
                s3SecretKey,
                s3Region,
                minecraftVersion,
                progressInterval,
                sourceConfig);
    }

    private static Map<String, String> s3SourceConfig(Map<String, String> env) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(S3SourceConnector.CONFIG_BUCKET, requireNonBlank(env, ENV_SOURCE_S3_BUCKET));
        putIfPresent(config, S3SourceConnector.CONFIG_ENDPOINT, env.get(ENV_SOURCE_S3_ENDPOINT));
        putIfPresent(config, S3SourceConnector.CONFIG_PREFIX, env.get(ENV_SOURCE_S3_PREFIX));
        putIfPresent(config, S3SourceConnector.CONFIG_ACCESS_KEY_ID, env.get(ENV_SOURCE_S3_ACCESS_KEY));
        putIfPresent(config, S3SourceConnector.CONFIG_SECRET_ACCESS_KEY, env.get(ENV_SOURCE_S3_SECRET_KEY));
        putIfPresent(config, S3SourceConnector.CONFIG_REGION, env.get(ENV_SOURCE_S3_REGION));
        return config;
    }

    private static Map<String, String> pterodactylSourceConfig(Map<String, String> env) {
        Map<String, String> config = new LinkedHashMap<>();
        config.put(PterodactylConnector.CONFIG_PANEL_URL, requireNonBlank(env, ENV_PTERODACTYL_PANEL_URL));
        config.put(PterodactylConnector.CONFIG_SERVER_ID, requireNonBlank(env, ENV_PTERODACTYL_SERVER_ID));
        config.put(PterodactylConnector.CONFIG_API_KEY, requireNonBlank(env, ENV_PTERODACTYL_API_KEY));
        config.put(PterodactylConnector.CONFIG_WORLD_PATHS, requireNonBlank(env, ENV_PTERODACTYL_WORLD_PATHS));
        return config;
    }

    private static void putIfPresent(Map<String, String> config, String key, String value) {
        if (value != null && !value.isBlank()) {
            config.put(key, value);
        }
    }

    private static String requireNonBlank(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(name + " is required but was not set.");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    private static long parsePositiveLong(Map<String, String> env, String name, long defaultValue) {
        String raw = env.get(name);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        long value;
        try {
            value = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ConfigurationException(name + " must be a positive integer, got: '" + raw + "'");
        }
        if (value <= 0) {
            throw new ConfigurationException(name + " must be a positive integer, got: '" + raw + "'");
        }
        return value;
    }

    public String sourceType() {
        return sourceType;
    }

    public String worldName() {
        return worldName;
    }

    /** The layout kind to force detection to, or {@code null} to auto-detect. */
    public String forcedLayout() {
        return forcedLayout;
    }

    public String sourceVersionId() {
        return sourceVersionId;
    }

    public String bundleBucket() {
        return bundleBucket;
    }

    public String bundleTenant() {
        return bundleTenant;
    }

    public String bundleWorldId() {
        return bundleWorldId;
    }

    public String bundleVersion() {
        return bundleVersion;
    }

    public String s3Endpoint() {
        return s3Endpoint;
    }

    public String s3AccessKey() {
        return s3AccessKey;
    }

    public String s3SecretKey() {
        return s3SecretKey;
    }

    public String s3Region() {
        return s3Region;
    }

    /** The Minecraft version to record in the manifest, or {@code null} if not supplied. */
    public String minecraftVersion() {
        return minecraftVersion;
    }

    public Duration progressInterval() {
        return progressInterval;
    }

    /** The source-type-specific connector configuration, keyed by each connector's own config keys. */
    public Map<String, String> sourceConfig() {
        return sourceConfig;
    }

    /** Thrown when the environment is missing a required variable or holds an invalid value. */
    public static final class ConfigurationException extends RuntimeException {

        ConfigurationException(String message) {
            super(message);
        }
    }
}
