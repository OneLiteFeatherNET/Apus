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
 * Observed state of a {@link WorldSource}. Every group is initialised in its field declaration
 * so a reconciler never has to null-check its way down to a leaf field.
 */
public class WorldSourceStatus {

    private String lastSeenVersion;
    private BundleRef latestBundle = new BundleRef();
    private String lastPollTime;
    private List<Condition> conditions = new ArrayList<>();

    public String getLastSeenVersion() {
        return lastSeenVersion;
    }

    public void setLastSeenVersion(String lastSeenVersion) {
        this.lastSeenVersion = lastSeenVersion;
    }

    public BundleRef getLatestBundle() {
        return latestBundle;
    }

    public void setLatestBundle(BundleRef latestBundle) {
        this.latestBundle = latestBundle;
    }

    public String getLastPollTime() {
        return lastPollTime;
    }

    public void setLastPollTime(String lastPollTime) {
        this.lastPollTime = lastPollTime;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }
}
