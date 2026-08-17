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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Desired state of a Rook CephObjectStoreUser. Plain data, no Kubernetes access.
 *
 * <p>Tolerates unmodelled fields like the status types do. A spec is read back as well as written:
 * this operator reads an existing user before touching it, and a field somebody set through
 * {@code kubectl} -- or one a Rook upgrade defaults in -- must not make that read throw.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class CephObjectStoreUserSpec {

    private String store;
    private String displayName;
    private Quotas quotas = new Quotas();

    public String getStore() {
        return store;
    }

    public void setStore(String store) {
        this.store = store;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public Quotas getQuotas() {
        return quotas;
    }

    public void setQuotas(Quotas quotas) {
        this.quotas = quotas;
    }

    /** Enforced by RGW, not by Apus. Exceeding it makes uploads fail. */
    public static class Quotas {
        private String maxSize;
        private Long maxObjects;
        private Integer maxBuckets;

        public String getMaxSize() {
            return maxSize;
        }

        public void setMaxSize(String maxSize) {
            this.maxSize = maxSize;
        }

        public Long getMaxObjects() {
            return maxObjects;
        }

        public void setMaxObjects(Long maxObjects) {
            this.maxObjects = maxObjects;
        }

        public Integer getMaxBuckets() {
            return maxBuckets;
        }

        public void setMaxBuckets(Integer maxBuckets) {
            this.maxBuckets = maxBuckets;
        }
    }
}
