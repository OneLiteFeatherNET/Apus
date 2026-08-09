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
package net.onelitefeather.apus.operator.api;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Label keys shared by every reconciler/builder that creates a Kubernetes resource, plus a
 * helper that produces the standard set.
 *
 * <p>Before this class existed, each of the tenant, bucket and render code paths invented its
 * own labelling (or none at all), so {@code kubectl get ... -l
 * app.kubernetes.io/managed-by=apus-operator} could not find everything Apus manages. Every
 * resource this operator creates should carry at least {@link #MANAGED_BY}.
 */
public final class Labels {

    /** Standard Kubernetes recommended label identifying the controller that manages a resource. */
    public static final String MANAGED_BY = "app.kubernetes.io/managed-by";

    /** Value of {@link #MANAGED_BY} for every resource this operator creates. */
    public static final String MANAGED_BY_VALUE = "apus-operator";

    /** Standard Kubernetes recommended label for the kind of resource/component. */
    public static final String NAME = "app.kubernetes.io/name";

    /** Standard Kubernetes recommended label identifying the specific instance/owner. */
    public static final String INSTANCE = "app.kubernetes.io/instance";

    /**
     * The tenant a resource belongs to, by name. Not unique on its own once a tenant can be
     * deleted and recreated with the same name -- see {@link #TENANT_UID}.
     */
    public static final String TENANT = "apus.onelitefeather.net/tenant";

    /**
     * The UID of the owning {@code Tenant} resource. A tenant name can be reused after
     * deletion, but its UID never is, so ownership checks must compare this label, not just
     * {@link #TENANT}.
     */
    public static final String TENANT_UID = "apus.onelitefeather.net/tenant-uid";

    /**
     * The {@code BlueMapMap} a per-map resource (e.g. an {@code ObjectBucketClaim}) belongs
     * to, by name. Mirrors {@link #TENANT}: not unique on its own once a map can be deleted
     * and recreated with the same name -- see {@link #MAP_UID}.
     */
    public static final String MAP = "apus.onelitefeather.net/map";

    /**
     * The UID of the owning {@code BlueMapMap} resource. Mirrors {@link #TENANT_UID}: a map
     * name can be reused after deletion, but its UID never is, so ownership checks must
     * compare this label, not just {@link #MAP}.
     */
    public static final String MAP_UID = "apus.onelitefeather.net/map-uid";

    /**
     * The {@code WorldSource} a per-source resource (e.g. a {@code WorldIngest} created by
     * {@code WorldSourceReconciler}) belongs to, by name. Mirrors {@link #MAP}: not unique on
     * its own once a source can be deleted and recreated with the same name -- see {@link
     * #SOURCE_UID}.
     */
    public static final String SOURCE = "apus.onelitefeather.net/world-source";

    /**
     * The UID of the owning {@code WorldSource} resource. Mirrors {@link #MAP_UID}: a source
     * name can be reused after deletion, but its UID never is, so ownership checks must
     * compare this label, not just {@link #SOURCE}.
     */
    public static final String SOURCE_UID = "apus.onelitefeather.net/world-source-uid";

    private Labels() {}

    /**
     * Builds the standard label set every resource the operator creates should carry.
     *
     * @param name a short, stable name for the kind of resource being labelled, e.g. {@code
     *     "bluemap-render"}
     * @param instance the name of the specific higher-level object this resource was created for
     * @return a fresh, mutable map so callers can add further labels on top
     */
    public static Map<String, String> standard(String name, String instance) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(MANAGED_BY, MANAGED_BY_VALUE);
        labels.put(NAME, name);
        labels.put(INSTANCE, instance);
        return labels;
    }
}
