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

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

/**
 * Presigned multipart uploads into the staging bucket (design spec §11.1) -- the actual bulk
 * data transfer for {@code POST /api/uploads} happens directly between the caller and S3, never
 * through this API. This class only ever hands out presigned URLs for {@code UploadPart}; {@code
 * CreateMultipartUpload}, {@code ListParts}, {@code CompleteMultipartUpload} and {@code
 * AbortMultipartUpload} are all performed here, directly, with this backend's own staging
 * credentials -- never presigned for a caller to invoke themselves.
 *
 * <p><b>Why completion is not presigned too.</b> {@code S3Presigner} can presign a {@code
 * CompleteMultipartUploadRequest} just as well as an {@code UploadPartRequest}. Handing that out
 * as well would mean this backend never sees the completion call at all -- and completion is the
 * one point in the whole multipart lifecycle where the *actual* total bytes uploaded are known
 * (via {@code ListParts}, which reads real, S3-recorded part sizes, not anything the client
 * claims). Keeping completion as an authenticated call this backend performs itself is what makes
 * {@link #completeUpload}'s size check a real enforcement point rather than a suggestion: a
 * caller who oversubscribes their declared size and then still uploads a huge part earns an
 * {@link AbortMultipartUploadRequest}, not a usable object.
 *
 * <p><b>Prefix confinement is structural, not advisory.</b> {@link #stagingKey} is the only place
 * an S3 key is ever built, and it is a pure function of a server-derived {@code namespace} (from
 * {@code TenantResolver}, itself from the caller's validated JWT -- never client input) plus a
 * {@code sourceName} the controller has already confirmed is a real {@code WorldSource} in that
 * same namespace. Whatever a caller passes as {@code version}/{@code fileName} therefore can only
 * ever select an object *within* {@code <prefix>/<namespace>/<sourceName>/...} -- S3 keys have no
 * {@code ..}-style traversal semantics, so there is no sequence of characters in either field that
 * escapes that subtree into a different tenant's.
 *
 * <p><b>What is, and is not, actually enforced on size -- read before trusting this class fully.</b>
 * See the phase 6 task report for the full analysis; the short version:
 *
 * <ul>
 *   <li><b>Enforced, verified:</b> {@link #completeUpload} sums the real, S3-recorded sizes of
 *       every part via {@code ListParts} and aborts (never completes) an upload whose actual
 *       total exceeds {@link #maxUploadBytes}. An oversized upload therefore never becomes a
 *       retrievable object, regardless of what any individual presigned part URL allowed through.
 *   <li><b>Enforced, empirically confirmed against real MinIO (2026-08-09, {@code
 *       MultipartUploadServiceIntegrationTest#aPartExceedingItsPresignedSizeIsRejectedBeforeItIsAccepted}):</b>
 *       {@link #createUpload} sets {@code Content-Length} on each presigned {@code UploadPart}
 *       request to the exact byte count that part was sized for. The AWS SDK v2 presigner
 *       includes {@code Content-Length} among that URL's signed headers, so a client sending more
 *       bytes than declared for a given part is rejected with HTTP 403 {@code
 *       SignatureDoesNotMatch} before those extra bytes are accepted -- confirmed by driving a
 *       real oversized {@code PUT} against a real MinIO instance, not inferred from SDK
 *       documentation. Not independently re-verified against Ceph RGW (the production backend
 *       design spec §9.1 names) -- both implement SigV4 presigned-URL validation the same way, but
 *       that specific claim is an inference from the MinIO result, not a second measurement.
 *   <li><b>Not this class's job at all, and does not need to be:</b> the tenant's actual storage
 *       budget. Design spec §10.2 is explicit that {@code Tenant.spec.storage.quota} is enforced
 *       by Ceph RGW itself, independent of anything the application does or gets wrong --
 *       {@link #maxUploadBytes} exists only to bound a single upload's blast radius (an absurd
 *       one-shot request), not to replace that quota.
 * </ul>
 */
@Singleton
public class MultipartUploadService {

    /** Hard defensive ceiling on issued part URLs, well under S3's own 10 000-part protocol limit. */
    private static final int MAX_PARTS = 2000;

    private static final Pattern SAFE_FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,255}");
    private static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9-]{1,128}");

    private final S3Client s3Client;
    private final S3Presigner presigner;
    private final String bucket;
    private final String prefix;
    private final long partSizeBytes;
    private final long maxUploadBytes;
    private final Duration urlExpiry;

    public MultipartUploadService(
            S3Client s3Client,
            S3Presigner presigner,
            @Value("${apus.staging.bucket}") String bucket,
            @Value("${apus.staging.prefix:staging/}") String prefix,
            @Value("${apus.staging.part-size-bytes:67108864}") long partSizeBytes,
            @Value("${apus.staging.max-upload-bytes:10737418240}") long maxUploadBytes,
            @Value("${apus.staging.url-expiry-seconds:900}") long urlExpirySeconds) {
        this.s3Client = s3Client;
        this.presigner = presigner;
        this.bucket = bucket;
        this.prefix = normalisePrefix(prefix);
        this.partSizeBytes = partSizeBytes;
        this.maxUploadBytes = maxUploadBytes;
        this.urlExpiry = Duration.ofSeconds(urlExpirySeconds);
    }

    /**
     * Initiates a multipart upload and presigns every part slot it will need for {@code
     * declaredSizeBytes}.
     *
     * @param namespace the caller's own namespace, from {@code TenantResolver} -- never anything
     *     else, see the class Javadoc
     * @param sourceName the target {@code WorldSource}'s name, already confirmed by the caller to
     *     exist (as type {@code upload}) in {@code namespace}
     * @param fileName the object's file name (e.g. {@code "world.tar.gz"}), kept as the staged
     *     key's final segment so its extension survives for {@code Archives.isArchive} at ingest
     *     time; validated against {@link #SAFE_FILE_NAME}
     * @param declaredSizeBytes the caller's declared total size -- used only to size/count parts
     *     and for the cheap upfront rejection in this method; the real check is {@link
     *     #completeUpload}'s
     * @throws BadRequestException if {@code fileName}/{@code declaredSizeBytes} are invalid, or
     *     the declared size would require issuing more than {@link #MAX_PARTS} presigned URLs
     */
    public CreateUploadResponse createUpload(String namespace, String sourceName, String fileName, long declaredSizeBytes) {
        requireSafe(fileName, SAFE_FILE_NAME, "fileName");
        if (declaredSizeBytes <= 0) {
            throw new BadRequestException("sizeBytes must be greater than zero");
        }
        if (declaredSizeBytes > maxUploadBytes) {
            throw new BadRequestException("sizeBytes exceeds the maximum allowed upload size of " + maxUploadBytes + " bytes");
        }

        String version = UUID.randomUUID().toString();
        String key = stagingKey(prefix, namespace, sourceName, version, fileName);

        CreateMultipartUploadResponse created = s3Client.createMultipartUpload(
                CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build());
        String uploadId = created.uploadId();

        int partCount = (int) Math.ceil((double) declaredSizeBytes / (double) partSizeBytes);
        if (partCount > MAX_PARTS) {
            // Nothing was uploaded yet -- abort immediately rather than leaving an empty
            // multipart upload dangling in S3 for no reason.
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
            throw new BadRequestException("sizeBytes would require more than " + MAX_PARTS + " parts");
        }

        List<CreateUploadResponse.PresignedPart> parts = new ArrayList<>(partCount);
        for (int partNumber = 1; partNumber <= partCount; partNumber++) {
            long thisPartSize =
                    (partNumber < partCount) ? partSizeBytes : declaredSizeBytes - partSizeBytes * (long) (partCount - 1);
            parts.add(presignPart(key, uploadId, partNumber, thisPartSize));
        }

        return new CreateUploadResponse(
                uploadId, bucket, key, version, fileName, parts, partSizeBytes, Instant.now().plus(urlExpiry));
    }

    private CreateUploadResponse.PresignedPart presignPart(String key, String uploadId, int partNumber, long partSize) {
        UploadPartRequest partRequest = UploadPartRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                // Pins this part's exact size into the presigned URL's signature -- empirically
                // confirmed (see the class Javadoc's "What is, and is not, actually enforced"
                // section) to make a larger upload fail with 403 SignatureDoesNotMatch.
                .contentLength(partSize)
                .build();
        UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                .signatureDuration(urlExpiry)
                .uploadPartRequest(partRequest)
                .build();
        PresignedUploadPartRequest presigned = presigner.presignUploadPart(presignRequest);
        return new CreateUploadResponse.PresignedPart(partNumber, partSize, presigned.url().toString());
    }

    /**
     * Finalises a multipart upload -- the real size enforcement point, see the class Javadoc.
     * Sums the actual, S3-recorded size of every uploaded part via {@code ListParts} and only
     * calls {@code CompleteMultipartUpload} if that real total is within {@link
     * #maxUploadBytes}; otherwise aborts the upload and rejects the request. The key is
     * recomputed from {@code namespace}/{@code sourceName}/{@code version}/{@code fileName}
     * exactly as {@link #createUpload} built it -- never taken from the caller directly -- so
     * completion is confined to the same namespace-scoped prefix creation was.
     *
     * @throws BadRequestException if no parts were uploaded, the real total exceeds {@link
     *     #maxUploadBytes} (the upload is aborted before this is thrown), or {@code version}/
     *     {@code fileName} fail their format checks
     */
    public CompleteUploadResponse completeUpload(
            String namespace, String sourceName, String version, String fileName, String uploadId) {
        requireSafe(fileName, SAFE_FILE_NAME, "fileName");
        requireSafe(version, SAFE_VERSION, "version");
        if (uploadId == null || uploadId.isBlank()) {
            throw new BadRequestException("uploadId must not be blank");
        }

        String key = stagingKey(prefix, namespace, sourceName, version, fileName);

        List<Part> allParts = listAllParts(key, uploadId);
        long totalBytes = allParts.stream().mapToLong(Part::size).sum();

        if (allParts.isEmpty()) {
            throw new BadRequestException("no parts were uploaded for this upload");
        }
        if (totalBytes > maxUploadBytes) {
            s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .uploadId(uploadId)
                    .build());
            throw new BadRequestException(
                    "upload's actual total size (" + totalBytes + " bytes) exceeds the maximum allowed ("
                            + maxUploadBytes + " bytes); the upload was aborted");
        }

        List<CompletedPart> completedParts = allParts.stream()
                .map(part -> CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build())
                .toList();

        s3Client.completeMultipartUpload(CompleteMultipartUploadRequest.builder()
                .bucket(bucket)
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                .build());

        return new CompleteUploadResponse(key, version, totalBytes, allParts.size());
    }

    /** Pages through every uploaded part -- authoritative, S3-recorded sizes/ETags, never trusting anything the caller claims. */
    private List<Part> listAllParts(String key, String uploadId) {
        List<Part> all = new ArrayList<>();
        Integer partNumberMarker = null;
        boolean truncated = true;
        while (truncated) {
            var requestBuilder = ListPartsRequest.builder().bucket(bucket).key(key).uploadId(uploadId);
            if (partNumberMarker != null) {
                requestBuilder.partNumberMarker(partNumberMarker);
            }
            ListPartsResponse response = s3Client.listParts(requestBuilder.build());
            all.addAll(response.parts());
            truncated = Boolean.TRUE.equals(response.isTruncated());
            if (truncated) {
                partNumberMarker = response.nextPartNumberMarker();
                if (partNumberMarker == null) {
                    break;
                }
            }
        }
        return all;
    }

    /**
     * The single place a staging S3 key is ever constructed -- see the class Javadoc's "Prefix
     * confinement is structural, not advisory". Package-private and {@code static} so it can be
     * unit-tested directly, with no S3 client/credentials/Micronaut context involved, proving the
     * confinement property for arbitrary (including adversarial) {@code sourceName}/{@code
     * version}/{@code fileName} inputs.
     */
    static String stagingKey(String prefix, String namespace, String sourceName, String version, String fileName) {
        return normalisePrefix(prefix) + namespace + "/" + sourceName + "/" + version + "/" + fileName;
    }

    private static String normalisePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return "";
        }
        return prefix.endsWith("/") ? prefix : prefix + "/";
    }

    private static void requireSafe(String value, Pattern pattern, String fieldName) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new BadRequestException(fieldName + " is missing or contains characters outside " + pattern.pattern());
        }
    }
}
