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
 * Observed state of a {@link BlueMapRender}. {@code progress} is initialised in its field
 * declaration so a reconciler never has to null-check its way down to a leaf field.
 */
public class BlueMapRenderStatus {

    /** Pending|Syncing|Rendering|Finalizing|Succeeded|Failed */
    private String phase;

    private Progress progress = new Progress();
    private String jobName;
    private String startTime;
    private String completionTime;
    private List<Condition> conditions = new ArrayList<>();

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public Progress getProgress() {
        return progress;
    }

    public void setProgress(Progress progress) {
        this.progress = progress;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getStartTime() {
        return startTime;
    }

    public void setStartTime(String startTime) {
        this.startTime = startTime;
    }

    public String getCompletionTime() {
        return completionTime;
    }

    public void setCompletionTime(String completionTime) {
        this.completionTime = completionTime;
    }

    public List<Condition> getConditions() {
        return conditions;
    }

    public void setConditions(List<Condition> conditions) {
        this.conditions = conditions;
    }

    /** How far the current render job has gotten. */
    public static class Progress {
        private double percent;
        private String currentMap;
        private long etaSeconds;
        private boolean degraded;

        public double getPercent() {
            return percent;
        }

        public void setPercent(double percent) {
            this.percent = percent;
        }

        public String getCurrentMap() {
            return currentMap;
        }

        public void setCurrentMap(String currentMap) {
            this.currentMap = currentMap;
        }

        public long getEtaSeconds() {
            return etaSeconds;
        }

        public void setEtaSeconds(long etaSeconds) {
            this.etaSeconds = etaSeconds;
        }

        public boolean isDegraded() {
            return degraded;
        }

        public void setDegraded(boolean degraded) {
            this.degraded = degraded;
        }
    }
}
