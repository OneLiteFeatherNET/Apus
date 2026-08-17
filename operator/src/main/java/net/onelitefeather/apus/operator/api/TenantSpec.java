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

    /**
     * Platform-set options for this tenant, and which of them it may not deviate from. Never
     * {@code null} -- an empty list means unregulated, and the tenant behaves as it did before
     * any policy existed.
     */
    private List<PolicyEntry> policy = new ArrayList<>();

    /** How this tenant's members are recognised in the identity provider. See {@link Identity}. */
    private Identity identity = new Identity();

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

    public List<PolicyEntry> getPolicy() {
        return policy;
    }

    /**
     * Absorbs {@code null}, which is what Fabric8 deserialises an absent field to. Every reader
     * treats "no policy" as "unregulated"; without this each of them would need the same null
     * check, and one of them would eventually forget it.
     */
    public void setPolicy(List<PolicyEntry> policy) {
        this.policy = policy == null ? new ArrayList<>() : policy;
    }

    public Identity getIdentity() {
        return identity;
    }

    /** Absorbs {@code null}, the same way {@link #setPolicy} does and for the same reason. */
    public void setIdentity(Identity identity) {
        this.identity = identity == null ? new Identity() : identity;
    }

    /**
     * Ties this tenant to a group in the identity provider, which is how a signed-in user is
     * recognised as one of its members.
     *
     * <p>Before this existed, {@code PrincipalResolver} in the {@code api} module read a claim
     * named {@code organization} that the app registration never emitted -- neither {@code
     * groupMembershipClaims} nor {@code optionalClaims} was configured -- so every user resolved
     * to "no tenant" and the tenant application had nothing to show anybody. A group id is
     * something the provider genuinely puts in a token, and it is the same identifier the
     * directory operations (teams, invitations) are scoped by.
     *
     * <p>Empty is allowed and means what it did before: membership is not derived from groups,
     * and no directory operation is permitted against this tenant -- rather than "any group",
     * which would make an unconfigured tenant the widest one on the platform.
     */
    public static class Identity {

        /** Object id of the group whose members belong to this tenant. Empty means unconfigured. */
        private String groupId;

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId == null || groupId.isBlank() ? null : groupId;
        }
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
