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
 * A single ingest run: extracts one world at one version out of a {@link WorldSource} and
 * transforms/loads it into the common bundle format {@link BlueMapMap} renders from. Namespaced:
 * an ingest run belongs to exactly one tenant's namespace, exactly like {@link WorldSource}.
 */
@Group("bluemap.onelitefeather.net")
@Version("v1alpha1")
@Kind("WorldIngest")
@Plural("worldingests")
@ShortNames("bmingest")
public class WorldIngest extends CustomResource<WorldIngestSpec, WorldIngestStatus> implements Namespaced {

    @Override
    protected WorldIngestSpec initSpec() {
        return new WorldIngestSpec();
    }

    @Override
    protected WorldIngestStatus initStatus() {
        return new WorldIngestStatus();
    }
}
