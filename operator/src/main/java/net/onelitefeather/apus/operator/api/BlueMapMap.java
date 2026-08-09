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

import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.ShortNames;
import io.fabric8.kubernetes.model.annotation.Version;

/**
 * A single BlueMap map belonging to a tenant. Namespaced: a map belongs to exactly one
 * tenant's namespace and must never be creatable across tenant boundaries.
 */
@Group("bluemap.onelitefeather.net")
@Version("v1alpha1")
@Kind("BlueMapMap")
@Plural("bluemapmaps")
@ShortNames("bmmap")
public class BlueMapMap extends CustomResource<BlueMapMapSpec, BlueMapMapStatus> implements Namespaced {

    @Override
    protected BlueMapMapSpec initSpec() {
        return new BlueMapMapSpec();
    }

    @Override
    protected BlueMapMapStatus initStatus() {
        return new BlueMapMapStatus();
    }
}
