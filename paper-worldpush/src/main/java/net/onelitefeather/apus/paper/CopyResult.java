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

import java.util.List;

/**
 * The outcome of one {@link IncrementalWorldCopier#copyChanged} call.
 *
 * @param copiedRelativePaths the region files actually (re-)copied into staging this run, in the
 *     same relative-path form used as their eventual S3 key suffix
 * @param copiedBytes total size of {@link #copiedRelativePaths}
 * @param unchangedCount how many region files were considered but found unchanged
 */
public record CopyResult(List<String> copiedRelativePaths, long copiedBytes, int unchangedCount) {

    public boolean isEmpty() {
        return copiedRelativePaths.isEmpty();
    }
}
