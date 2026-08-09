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

import java.net.URI;

/**
 * The plugin's complete configuration, read from {@code config.yml} (via {@link ConfigSource})
 * and validated eagerly.
 *
 * <p>{@link #from(ConfigSource)} is the single place every required key is checked. It either
 * returns a fully valid configuration or throws {@link ConfigurationException} before any push
 * cycle is scheduled -- mirroring {@code ingest.IngestConfig}'s "fail before anything runs"
 * contract in the sibling module.
 *
 * <p>Two distinct credentials are held here, deliberately not conflated (see {@code config.yml}'s
 * comments for the full rationale):
 *
 * <ul>
 *   <li>{@link #s3AccessKey()}/{@link #s3SecretKey()} -- tenant-scoped S3 bucket credentials,
 *       used to write region files directly into the staging prefix.
 *   <li>{@link #pushToken()} -- a narrow, tenant-bound {@code world:push} service token (§10.3 of
 *       the design spec), used only to authenticate the completion report to the Apus API.
 * </ul>
 */
public final class WorldPushConfig {

    private final String worldName;
    private final String tenant;
    private final String pushToken;
    private final String stagingDirectory;
    private final String s3Endpoint;
    private final String s3Bucket;
    private final String s3Region;
    private final String s3AccessKey;
    private final String s3SecretKey;
    private final String s3StagingPrefix;
    private final URI apusApiBaseUrl;
    private final long intervalMinutes;

    private WorldPushConfig(
            String worldName,
            String tenant,
            String pushToken,
            String stagingDirectory,
            String s3Endpoint,
            String s3Bucket,
            String s3Region,
            String s3AccessKey,
            String s3SecretKey,
            String s3StagingPrefix,
            URI apusApiBaseUrl,
            long intervalMinutes) {
        this.worldName = worldName;
        this.tenant = tenant;
        this.pushToken = pushToken;
        this.stagingDirectory = stagingDirectory;
        this.s3Endpoint = s3Endpoint;
        this.s3Bucket = s3Bucket;
        this.s3Region = s3Region;
        this.s3AccessKey = s3AccessKey;
        this.s3SecretKey = s3SecretKey;
        this.s3StagingPrefix = s3StagingPrefix;
        this.apusApiBaseUrl = apusApiBaseUrl;
        this.intervalMinutes = intervalMinutes;
    }

    /**
     * Reads and validates every configuration value the plugin needs from {@code source}.
     *
     * @throws ConfigurationException if a required key is missing/blank, or a value cannot be
     *     parsed (e.g. {@code apus.api-base-url} is not a valid URI)
     */
    public static WorldPushConfig from(ConfigSource source) {
        String worldName = requireNonBlank(source, "world-name");
        String tenant = requireNonBlank(source, "tenant");
        String pushToken = requireNonBlank(source, "push-token");
        String stagingDirectory = orDefault(source.getString("staging-directory"), "apus-worldpush-staging");

        String s3Endpoint = requireNonBlank(source, "s3.endpoint");
        String s3Bucket = requireNonBlank(source, "s3.bucket");
        String s3Region = orDefault(source.getString("s3.region"), "us-east-1");
        String s3AccessKey = requireNonBlank(source, "s3.access-key");
        String s3SecretKey = requireNonBlank(source, "s3.secret-key");
        String s3StagingPrefix = normalizePrefix(orDefault(source.getString("s3.staging-prefix"), "staging/"));

        String apiBaseUrlRaw = requireNonBlank(source, "apus.api-base-url");
        URI apusApiBaseUrl;
        try {
            apusApiBaseUrl = URI.create(apiBaseUrlRaw);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("apus.api-base-url is not a valid URI: '" + apiBaseUrlRaw + "'");
        }
        if (apusApiBaseUrl.getScheme() == null || apusApiBaseUrl.getHost() == null) {
            throw new ConfigurationException(
                    "apus.api-base-url must be an absolute URL with a scheme and host, got: '" + apiBaseUrlRaw + "'");
        }

        long intervalMinutes = source.getLong("schedule.interval-minutes", 30);
        if (intervalMinutes <= 0) {
            throw new ConfigurationException("schedule.interval-minutes must be a positive integer, got: " + intervalMinutes);
        }

        return new WorldPushConfig(
                worldName,
                tenant,
                pushToken,
                stagingDirectory,
                s3Endpoint,
                s3Bucket,
                s3Region,
                s3AccessKey,
                s3SecretKey,
                s3StagingPrefix,
                apusApiBaseUrl,
                intervalMinutes);
    }

    private static String normalizePrefix(String prefix) {
        String trimmed = prefix.startsWith("/") ? prefix.substring(1) : prefix;
        return trimmed.endsWith("/") || trimmed.isEmpty() ? trimmed : trimmed + "/";
    }

    private static String orDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    private static String requireNonBlank(ConfigSource source, String path) {
        String value = source.getString(path);
        if (value == null || value.isBlank()) {
            throw new ConfigurationException(path + " is required but was not set in config.yml.");
        }
        return value;
    }

    public String worldName() {
        return worldName;
    }

    public String tenant() {
        return tenant;
    }

    /** The narrowly-scoped {@code world:push} service token -- never log this value. */
    public String pushToken() {
        return pushToken;
    }

    /** Relative or absolute path to the local staging directory; resolved against the plugin's data folder. */
    public String stagingDirectory() {
        return stagingDirectory;
    }

    public String s3Endpoint() {
        return s3Endpoint;
    }

    public String s3Bucket() {
        return s3Bucket;
    }

    public String s3Region() {
        return s3Region;
    }

    /** Tenant-scoped S3 access key -- never log this value. */
    public String s3AccessKey() {
        return s3AccessKey;
    }

    /** Tenant-scoped S3 secret key -- never log this value. */
    public String s3SecretKey() {
        return s3SecretKey;
    }

    /** Key prefix within {@link #s3Bucket()} that staged region files are uploaded under, always ending in {@code "/"}. */
    public String s3StagingPrefix() {
        return s3StagingPrefix;
    }

    public URI apusApiBaseUrl() {
        return apusApiBaseUrl;
    }

    public long intervalMinutes() {
        return intervalMinutes;
    }

    /** Thrown when {@code config.yml} is missing a required key or holds an invalid value. */
    public static final class ConfigurationException extends RuntimeException {

        ConfigurationException(String message) {
            super(message);
        }
    }
}
