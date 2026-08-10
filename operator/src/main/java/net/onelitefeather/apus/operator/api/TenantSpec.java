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

import java.util.ArrayList;
import java.util.List;

/** Desired state of a tenant. Plain data, no Kubernetes access. */
public class TenantSpec {

    private String displayName;
    private StorageQuota storage = new StorageQuota();
    private Hosting hosting = new Hosting();

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

    public Hosting getHosting() {
        return hosting;
    }

    public void setHosting(Hosting hosting) {
        this.hosting = hosting;
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

    /**
     * Constrains which hostnames {@code BlueMapHosting} resources in this tenant's namespace may
     * request (design spec §8.1). Enforced by {@code
     * net.onelitefeather.apus.operator.hosting.BlueMapHostingReconciler}, not by this class or
     * the CRD schema -- a {@code BlueMapHosting} carries no reference back to its tenant, so the
     * check can only happen once the reconciler has resolved the tenant owning its namespace.
     *
     * <p>An empty {@link #allowedDomains} is deliberately treated as "no hosting permitted yet",
     * not "anything goes": it far more often means a tenant simply has not been configured for
     * hosting at all than that a platform administrator consciously decided to let it claim any
     * hostname on the internet.
     */
    public static class Hosting {

        /**
         * Hostnames (or single-level wildcards, e.g. {@code *.friends.example.net}) a {@code
         * BlueMapHosting} in this tenant may use. Empty by default -- see the class Javadoc for
         * why that means "not allowed" rather than "unrestricted".
         */
        private List<String> allowedDomains = new ArrayList<>();

        public List<String> getAllowedDomains() {
            return allowedDomains;
        }

        public void setAllowedDomains(List<String> allowedDomains) {
            this.allowedDomains = allowedDomains;
        }
    }
}
