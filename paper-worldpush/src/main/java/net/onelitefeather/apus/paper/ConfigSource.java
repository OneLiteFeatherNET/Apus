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

/**
 * The handful of config-file reads {@link WorldPushConfig} needs, kept deliberately narrow so
 * tests can supply a plain in-memory implementation instead of a Bukkit {@code
 * FileConfiguration} -- the same "depend on the smallest possible interface" pattern {@code
 * ingest.S3Client} already uses for the same reason.
 *
 * <p>Paths are dotted, mirroring {@code config.yml}'s nesting (e.g. {@code "s3.access-key"}).
 */
public interface ConfigSource {

    /** Returns the string at {@code path}, or {@code null} if absent or not a string. */
    String getString(String path);

    /** Returns the long at {@code path}, or {@code defaultValue} if absent or not a number. */
    long getLong(String path, long defaultValue);
}
