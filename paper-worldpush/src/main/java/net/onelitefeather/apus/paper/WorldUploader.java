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

import java.nio.file.Path;

/**
 * The one upload operation {@link PushCycleRunner} needs, kept deliberately narrow -- mirrors
 * {@code ingest.S3Client}'s reasoning in the sibling module: tests substitute an in-memory fake
 * instead of talking to real S3-compatible storage, and the real implementation ({@link
 * S3WorldUploader}) is the only place that knows about the AWS SDK.
 */
public interface WorldUploader {

    /** Uploads {@code localFile}'s current contents to {@code s3Key}, overwriting any object already there. */
    void upload(Path localFile, String s3Key);
}
