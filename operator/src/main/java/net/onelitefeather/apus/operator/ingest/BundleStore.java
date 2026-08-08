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
package net.onelitefeather.apus.operator.ingest;

import java.time.Instant;
import java.util.List;

/**
 * The two bundle-bucket operations {@link WorldIngestReconciler}'s retention enforcement needs,
 * kept deliberately narrow -- mirrors {@code net.onelitefeather.apus.ingest.S3Client}'s "one
 * interface, real implementation plus an easily fakeable one for tests" shape -- so a test can
 * substitute an in-memory fake instead of talking to real S3-compatible storage.
 */
public interface BundleStore {

    /** One bundle version found under a world's prefix in the bucket. */
    record BundleVersion(String version, Instant lastModified) {}

    /**
     * Lists every bundle version currently written for {@code tenant}/{@code worldId}, in no
     * particular order -- callers sort as needed.
     */
    List<BundleVersion> listVersions(String tenant, String worldId, String bundleBucket);

    /** Deletes every object under one bundle version's prefix. */
    void deleteVersion(String tenant, String worldId, String version, String bundleBucket);
}
