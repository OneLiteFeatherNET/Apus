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

/** Desired state of a tenant. Plain data, no Kubernetes access. */
public class TenantSpec {

    private String displayName;
    private StorageQuota storage = new StorageQuota();

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public StorageQuota getStorage() {
        return storage;
    }

    public void setStorage(StorageQuota storage) {
        this.storage = storage;
    }

    /** Hard storage limit, enforced by Ceph rather than by this operator. */
    public static class StorageQuota {
        private String quota = "100Gi";
        private Long maxObjects;

        public String getQuota() {
            return quota;
        }

        public void setQuota(String quota) {
            this.quota = quota;
        }

        public Long getMaxObjects() {
            return maxObjects;
        }

        public void setMaxObjects(Long maxObjects) {
            this.maxObjects = maxObjects;
        }
    }
}
