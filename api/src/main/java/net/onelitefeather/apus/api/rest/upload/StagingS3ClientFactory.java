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

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * The {@link S3Client}/{@link S3Presigner} pair {@link MultipartUploadService} uses against the
 * platform-wide staging bucket -- one shared bucket, tenant isolation coming entirely from the
 * key prefix each presigned URL is scoped to (see that class's Javadoc), the same "one bucket,
 * many tenant-scoped prefixes" reading of design spec §11.1 the task brief's "to the staging
 * prefix of exactly this tenant" language implies. Credentials are therefore
 * platform-wide, not per-tenant -- mirroring how design spec §10.2 already describes the RGW
 * admin-ops credentials used for quota polling ("the credentials for that are platform-wide, not
 * tenant-bound").
 *
 * <p>{@code @Value} injection directly into {@code @Factory} methods, not a {@code
 * @ConfigurationProperties} class -- matches this module's existing convention (see {@code
 * net.onelitefeather.apus.api.events.LogSourceFactory}). No property here has a hardcoded
 * default that would silently point at a real bucket; every required one fails Micronaut startup
 * with a clear "missing configuration" error if unset, the same fail-fast posture {@code
 * application.yml}'s JWT properties already have.
 */
@Factory
class StagingS3ClientFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(StagingS3ClientFactory.class);

    private static final String DEFAULT_REGION = "us-east-1";

    /**
     * Logs how the staging client is addressed, never what it authenticates with: an access key
     * id and a secret access key are both credentials, and neither appears in any log line or
     * exception message this class can produce (docs/logging-and-tracing.md).
     */
    private static void logConfiguration(String what, String endpoint, String region, boolean staticCredentials) {
        LOGGER.info(
                "staging S3 {} configured (endpoint={}, region={}, credentials={})",
                what,
                (endpoint == null || endpoint.isBlank()) ? "aws-default" : endpoint,
                region,
                staticCredentials ? "static" : "default-provider-chain");
    }

    @Singleton
    S3Client stagingS3Client(
            @Value("${apus.staging.endpoint:}") String endpoint,
            @Value("${apus.staging.region:" + DEFAULT_REGION + "}") String region,
            @Value("${apus.staging.access-key-id:}") String accessKeyId,
            @Value("${apus.staging.secret-access-key:}") String secretAccessKey) {
        logConfiguration("client", endpoint, region, hasStaticCredentials(accessKeyId, secretAccessKey));
        var builder = S3Client.builder().region(Region.of(region)).credentialsProvider(credentials(accessKeyId, secretAccessKey));
        if (endpoint != null && !endpoint.isBlank()) {
            // S3-compatible stores (Rook/Ceph, MinIO, ...) need an endpoint override and
            // path-style bucket addressing -- see S3SourceConnector.buildClient (ingest module)
            // for the same reasoning applied to a pull source's own client.
            builder = builder.endpointOverride(URI.create(endpoint)).forcePathStyle(true);
        }
        return builder.build();
    }

    @Singleton
    S3Presigner stagingS3Presigner(
            @Value("${apus.staging.endpoint:}") String endpoint,
            @Value("${apus.staging.region:" + DEFAULT_REGION + "}") String region,
            @Value("${apus.staging.access-key-id:}") String accessKeyId,
            @Value("${apus.staging.secret-access-key:}") String secretAccessKey) {
        logConfiguration("presigner", endpoint, region, hasStaticCredentials(accessKeyId, secretAccessKey));
        var builder =
                S3Presigner.builder().region(Region.of(region)).credentialsProvider(credentials(accessKeyId, secretAccessKey));
        if (endpoint != null && !endpoint.isBlank()) {
            builder = builder.endpointOverride(URI.create(endpoint))
                    // Path-style addressing must be requested separately from the presigner's own
                    // service configuration -- endpointOverride() alone does not imply it, and a
                    // presigned URL built for virtual-hosted-style addressing against a
                    // non-AWS-DNS endpoint would simply not resolve for whoever tries to use it.
                    // Checksum validation is disabled for the same reason S3-compatible stores
                    // generally need it off for presigned uploads: the SDK would otherwise try to
                    // add a checksum trailer/header the presigned request was never signed to
                    // include, breaking the signature for whoever performs the actual PUT.
                    .serviceConfiguration(S3Configuration.builder()
                            .pathStyleAccessEnabled(true)
                            .checksumValidationEnabled(false)
                            .build());
        }
        return builder.build();
    }

    private static boolean hasStaticCredentials(String accessKeyId, String secretAccessKey) {
        return accessKeyId != null && !accessKeyId.isBlank() && secretAccessKey != null && !secretAccessKey.isBlank();
    }

    private static AwsCredentialsProvider credentials(String accessKeyId, String secretAccessKey) {
        if (hasStaticCredentials(accessKeyId, secretAccessKey)) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
        }
        return DefaultCredentialsProvider.builder().build();
    }
}
