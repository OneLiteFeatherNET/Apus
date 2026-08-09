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

/**
 * Observed state of a {@link BlueMapMap}. Every group is initialised in its field
 * declaration so a reconciler never has to null-check its way down to a leaf field.
 */
public class BlueMapMapStatus {

    private Bucket bucket = new Bucket();
    private LatestRender latestRender = new LatestRender();
    private List<Condition> conditions = new ArrayList<>();

    public Bucket getBucket() {
        return bucket;
    }

    public void setBucket(Bucket bucket) {
        this.bucket = bucket;
    }

    public LatestRender getLatestRender() {
        return latestRender;
    }

    public void setLatestRender(LatestRender latestRender) {
        this.latestRender = latestRender;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    /** The bucket claim backing this map's rendered output. */
    public static class Bucket {
        private String name;
        private String endpoint;
        private String secretName;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getSecretName() {
            return secretName;
        }

        public void setSecretName(String secretName) {
            this.secretName = secretName;
        }
    }

    /** The most recent {@link BlueMapRender} triggered for this map. */
    public static class LatestRender {
        private String name;
        private String phase;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPhase() {
            return phase;
        }

        public void setPhase(String phase) {
            this.phase = phase;
        }
    }
}
