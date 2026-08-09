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
package net.onelitefeather.apus.operator.rook;

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * Rook's ObjectBucketClaim, modelled with only the fields Apus uses.
 *
 * <p>Apus does not manage S3 itself: creating one of these makes Rook provision the
 * bucket and drop a credentials Secret and a ConfigMap into the same namespace.
 * This class is a client-side model of a CRD Rook owns — it must never be fed to
 * our own CRD generator.
 */
@Group("objectbucket.io")
@Version("v1alpha1")
@Kind("ObjectBucketClaim")
@Plural("objectbucketclaims")
public class ObjectBucketClaim extends CustomResource<ObjectBucketClaimSpec, ObjectBucketClaimStatus>
        implements Namespaced {

    @Override
    protected ObjectBucketClaimSpec initSpec() {
        return new ObjectBucketClaimSpec();
    }

    @Override
    protected ObjectBucketClaimStatus initStatus() {
        return new ObjectBucketClaimStatus();
    }
}
