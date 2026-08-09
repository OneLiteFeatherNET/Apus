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

import org.bukkit.configuration.file.FileConfiguration;

/** Adapts Bukkit's {@link FileConfiguration} (backing {@code config.yml}) to {@link ConfigSource}. */
public final class BukkitConfigSource implements ConfigSource {

    private final FileConfiguration delegate;

    public BukkitConfigSource(FileConfiguration delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getString(String path) {
        return delegate.getString(path);
    }

    @Override
    public long getLong(String path, long defaultValue) {
        return delegate.getLong(path, defaultValue);
    }
}
