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
 * Rook's CephObjectStoreUser, modelled with only the fields Apus uses.
 *
 * <p>This is where a tenant's storage limit lives. Because every bucket of a tenant
 * is owned by this user, RGW enforces the quota across all of them — the limit holds
 * even if the application miscounts.
 */
@Group("ceph.rook.io")
@Version("v1")
@Kind("CephObjectStoreUser")
@Plural("cephobjectstoreusers")
public class CephObjectStoreUser
        extends CustomResource<CephObjectStoreUserSpec, CephObjectStoreUserStatus> implements Namespaced {

    @Override
    protected CephObjectStoreUserSpec initSpec() {
        return new CephObjectStoreUserSpec();
    }

    @Override
    protected CephObjectStoreUserStatus initStatus() {
        return new CephObjectStoreUserStatus();
    }
}
