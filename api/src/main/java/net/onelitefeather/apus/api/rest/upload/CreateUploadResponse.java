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
package net.onelitefeather.apus.api.rest.upload;

import io.micronaut.serde.annotation.Serdeable;
import java.time.Instant;
import java.util.List;

/**
 * {@code POST /api/uploads}'s response: everything the caller needs to upload every part
 * directly to S3 and then call {@code POST /api/uploads/{uploadId}/complete}. Carries no
 * credentials -- each {@link PresignedPart#url()} already has its own signature embedded; that is
 * the entire point of a presigned URL.
 */
@Serdeable
public record CreateUploadResponse(
        String uploadId,
        String bucket,
        String key,
        String version,
        String fileName,
        List<PresignedPart> parts,
        long partSizeBytes,
        Instant expiresAt) {

    /** One presigned {@code UploadPart} slot -- the caller {@code PUT}s that part's bytes directly to {@code url}. */
    @Serdeable
    public record PresignedPart(int partNumber, long sizeBytes, String url) {}
}
