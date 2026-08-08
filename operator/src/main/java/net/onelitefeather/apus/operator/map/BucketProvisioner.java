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
package net.onelitefeather.apus.operator.map;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.Optional;
import net.onelitefeather.apus.operator.OperatorConfig;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import net.onelitefeather.apus.operator.api.Labels;
import net.onelitefeather.apus.operator.rook.ObjectBucketClaim;

/**
 * Provisions the S3 bucket a {@link BlueMapMap} stores its rendered output in, by creating a
 * Rook {@link ObjectBucketClaim}.
 *
 * <p>The claim is deliberately created in the map's own (tenant) namespace, not in the Rook
 * namespace from {@link OperatorConfig#rookNamespace()}. Rook always writes the resulting
 * credentials Secret and ConfigMap into the same namespace as the claim, so keeping the claim
 * anywhere else would require copying a Secret across a namespace boundary — exactly the kind
 * of cross-tenant credential leak the rest of the cluster's conventions try to avoid. This is a
 * deliberate exception to the "central" convention, not an oversight.
 *
 * <p>Once Rook binds the claim, it writes a Secret (named after the claim) into the same
 * namespace, containing the keys {@code AWS_ACCESS_KEY_ID} and {@code AWS_SECRET_ACCESS_KEY}.
 * {@link net.onelitefeather.apus.operator.render.RenderJobBuilder} references that Secret by
 * name and reads exactly those two keys via {@code secretKeyRef} — this is Rook's contract, not
 * something Apus controls, so callers must not rename or reshape it.
 */
public final class BucketProvisioner {

    /**
     * S3 bucket names are limited to 63 characters (RFC-compliant DNS label rules); Rook/RGW
     * enforces the same limit. Failing fast here gives a clear error instead of an opaque
     * rejection from Rook once the claim is submitted.
     */
    private static final int MAX_BUCKET_NAME_LENGTH = 63;

    private final KubernetesClient client;
    private final OperatorConfig config;

    public BucketProvisioner(KubernetesClient client, OperatorConfig config) {
        this.client = client;
        this.config = config;
    }

    /**
     * Ensures an {@link ObjectBucketClaim} exists for the given map, creating it on first call.
     *
     * @param map the map to provision storage for
     * @param cephUser the Ceph object-store user (from Task 3's tenant reconciler) that owns
     *     the bucket; recorded so Rook grants it access
     * @return the bound claim, or empty while Rook is still provisioning
     * @throws IllegalArgumentException if the resulting bucket name exceeds the 63-character S3
     *     limit
     */
    public Optional<ObjectBucketClaim> ensureBucket(BlueMapMap map, String cephUser) {
        String namespace = map.getMetadata().getNamespace();
        String name = map.getMetadata().getName();

        ObjectBucketClaim existing =
                client.resources(ObjectBucketClaim.class).inNamespace(namespace).withName(name).get();

        if (existing == null) {
            String bucketName = cephUser + "-" + name;
            if (bucketName.length() > MAX_BUCKET_NAME_LENGTH) {
                throw new IllegalArgumentException("bucket name '%s' is %d characters long, exceeding the S3 limit of %d"
                        .formatted(bucketName, bucketName.length(), MAX_BUCKET_NAME_LENGTH));
            }

            ObjectBucketClaim claim = new ObjectBucketClaim();
            claim.setMetadata(new ObjectMetaBuilder()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(Labels.standard("bluemap-bucket-claim", name))
                    .build());
            claim.getSpec().setBucketName(bucketName);
            claim.getSpec().setStorageClassName(config.bucketStorageClass());
            claim.getSpec().getAdditionalConfig().put("bucketOwner", cephUser);

            client.resources(ObjectBucketClaim.class).inNamespace(namespace).resource(claim).create();
            return Optional.empty();
        }

        if ("Bound".equals(existing.getStatus().getPhase())) {
            return Optional.of(existing);
        }
        return Optional.empty();
    }
}
