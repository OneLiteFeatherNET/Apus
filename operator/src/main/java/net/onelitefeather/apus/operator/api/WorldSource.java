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
 * A source of Minecraft world data that Apus can ingest -- an S3 bucket, a Pterodactyl panel, a
 * manual upload, or a push target. Namespaced: a source belongs to exactly one tenant's
 * namespace, exactly like {@link BlueMapMap}.
 */
@Group("bluemap.onelitefeather.net")
@Version("v1alpha1")
@Kind("WorldSource")
@Plural("worldsources")
@ShortNames("bmsource")
public class WorldSource extends CustomResource<WorldSourceSpec, WorldSourceStatus> implements Namespaced {

    @Override
    protected WorldSourceSpec initSpec() {
        return new WorldSourceSpec();
    }

    @Override
    protected WorldSourceStatus initStatus() {
        return new WorldSourceStatus();
    }

    /** One world this source exposes for ingest, and how its on-disk layout should be detected. */
    public static class WorldSelector {

        private String name;

        /** "auto" makes the ingest job detect the layout (vanilla/Paper/multiverse/...) itself. */
        private String layout = "auto";

        /**
         * The Minecraft version this world runs under, e.g. {@code "1.21.10"} -- recorded
         * verbatim into {@code manifest.minecraftVersion} for every bundle ingested from this
         * selector.
         *
         * <p><b>Why this lives here instead of being read from {@code level.dat}.</b> {@code
         * level.dat} is NBT, not JSON/plain text, and this project intentionally does not carry
         * an NBT parsing dependency (see {@code ingest/README.md}'s connector-only dependency
         * policy). Since {@code level.dat} is bundled starting from this same fix (see the
         * ingest layer's D1 fix), a future change could still read it back out once an NBT
         * reader exists -- but a required manifest field cannot stay permanently unset waiting
         * for that; a user-supplied value the tenant already knows (the version they run) is the
         * simplest correct answer available today. {@code null}/unset means the field is left
         * absent on the bundle, exactly as it always has been -- this is purely additive.
         */
        private String minecraftVersion;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getLayout() {
            return layout;
        }

        public void setLayout(String layout) {
            this.layout = layout;
        }

        public String getMinecraftVersion() {
            return minecraftVersion;
        }

        public void setMinecraftVersion(String minecraftVersion) {
            this.minecraftVersion = minecraftVersion;
        }
    }
}
