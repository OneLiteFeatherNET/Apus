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

import io.fabric8.kubernetes.api.model.Condition;
import java.util.ArrayList;
import java.util.List;

/** Observed state of a tenant. */
public class TenantStatus {

    private String namespace;
    private String objectStoreUser;
    private Long storageUsedBytes;
    private String pushTokenSecret;
    private List<Condition> conditions = new ArrayList<>();

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getObjectStoreUser() {
        return objectStoreUser;
    }

    public void setObjectStoreUser(String objectStoreUser) {
        this.objectStoreUser = objectStoreUser;
    }

    public Long getStorageUsedBytes() {
        return storageUsedBytes;
    }

    public void setStorageUsedBytes(Long storageUsedBytes) {
        this.storageUsedBytes = storageUsedBytes;
    }

    /**
     * The name of the {@code Secret} carrying this tenant's {@code world:push} service token, or
     * {@code null} if none has been provisioned yet. Deliberately only the Secret's name (a
     * fixed, non-secret constant, {@code PushTokenSecrets.SECRET_NAME}) -- never the token value
     * itself, which must never appear in a Custom Resource's status, in an event, or in a log
     * line. This field says at most "a token exists, here is where"; reading its value always
     * requires a separate, RBAC-guarded {@code Secret} read.
     */
    public String getPushTokenSecret() {
        return pushTokenSecret;
    }

    public void setPushTokenSecret(String pushTokenSecret) {
        this.pushTokenSecret = pushTokenSecret;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }
}
