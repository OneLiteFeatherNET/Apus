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
 * The last known state of one region file, as recorded after it was last copied into staging.
 *
 * <p>{@code size}/{@code lastModifiedMillis} are the cheap "did the OS report a change" check;
 * {@code checksum} (SHA-256, hex-encoded) is only computed when that cheap check trips, to
 * confirm the content actually differs -- see {@link IncrementalWorldCopier}'s class Javadoc for
 * why both signals are used together.
 */
public record RegionFileState(long size, long lastModifiedMillis, String checksum) {

    /** Serialises to the single-line format {@link CopyState} persists, e.g. {@code "1024:1700000000000:ab12..."}. */
    String encode() {
        return size + ":" + lastModifiedMillis + ":" + checksum;
    }

    /** Parses the format {@link #encode()} produces; returns {@code null} if {@code line} is malformed. */
    static RegionFileState decode(String line) {
        String[] parts = line.split(":", 3);
        if (parts.length != 3) {
            return null;
        }
        try {
            return new RegionFileState(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
