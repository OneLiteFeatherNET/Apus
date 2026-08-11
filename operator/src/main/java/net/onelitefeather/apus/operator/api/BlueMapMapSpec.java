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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desired state of a {@link BlueMapMap}. Plain data, no Kubernetes access.
 *
 * <p>Every group is initialised in its field declaration so a reconciler (or a test) never
 * has to null-check its way down to a leaf field.
 */
public class BlueMapMapSpec {

    private Source source = new Source();
    private Trigger trigger = new Trigger();
    private BlueMapSettings bluemap = new BlueMapSettings();
    private Storage storage = new Storage();
    private Resources resources = new Resources();

    /** Sharding is a Phase 4 concern; anything above 1 is not yet honoured. */
    private int shards = 1;

    private int historyLimit = 10;

    /** §9.4: deleting a BlueMapMap must never destroy render work that already ran. */
    private boolean purgeOnDelete = false;

    public Source getSource() {
        return source;
    }

    public void setSource(Source source) {
        this.source = source;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public void setTrigger(Trigger trigger) {
        this.trigger = trigger;
    }

    public BlueMapSettings getBluemap() {
        return bluemap;
    }

    public void setBluemap(BlueMapSettings bluemap) {
        this.bluemap = bluemap;
    }

    public Storage getStorage() {
        return storage;
    }

    public void setStorage(Storage storage) {
        this.storage = storage;
    }

    public Resources getResources() {
        return resources;
    }

    public void setResources(Resources resources) {
        this.resources = resources;
    }

    public int getShards() {
        return shards;
    }

    public void setShards(int shards) {
        this.shards = shards;
    }

    public int getHistoryLimit() {
        return historyLimit;
    }

    public void setHistoryLimit(int historyLimit) {
        this.historyLimit = historyLimit;
    }

    public boolean isPurgeOnDelete() {
        return purgeOnDelete;
    }

    public void setPurgeOnDelete(boolean purgeOnDelete) {
        this.purgeOnDelete = purgeOnDelete;
    }

    /** Where the world data this map renders comes from. */
    public static class Source {
        private Ref sourceRef = new Ref();
        private String world;
        private String dimension;

        public Ref getSourceRef() {
            return sourceRef;
        }

        public void setSourceRef(Ref sourceRef) {
            this.sourceRef = sourceRef;
        }

        public String getWorld() {
            return world;
        }

        public void setWorld(String world) {
            this.world = world;
        }

        public String getDimension() {
            return dimension;
        }

        public void setDimension(String dimension) {
            this.dimension = dimension;
        }
    }

    /** When a new {@link BlueMapRender} should be started for this map. */
    public static class Trigger {
        private boolean onNewBundle;
        private String schedule;

        /**
         * Two renders writing the same map storage concurrently can leave it inconsistent
         * (§7.3), so the default forbids overlap.
         */
        private String concurrencyPolicy = "Forbid";

        public boolean isOnNewBundle() {
            return onNewBundle;
        }

        public void setOnNewBundle(boolean onNewBundle) {
            this.onNewBundle = onNewBundle;
        }

        public String getSchedule() {
            return schedule;
        }

        public void setSchedule(String schedule) {
            this.schedule = schedule;
        }

        public String getConcurrencyPolicy() {
            return concurrencyPolicy;
        }

        public void setConcurrencyPolicy(String concurrencyPolicy) {
            this.concurrencyPolicy = concurrencyPolicy;
        }
    }

    /** BlueMap-specific rendering settings. */
    public static class BlueMapSettings {
        private String version;
        private String minecraftVersion;
        private Map<String, String> configOverrides = new LinkedHashMap<>();

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getMinecraftVersion() {
            return minecraftVersion;
        }

        public void setMinecraftVersion(String minecraftVersion) {
            this.minecraftVersion = minecraftVersion;
        }

        public Map<String, String> getConfigOverrides() {
            return configOverrides;
        }

        public void setConfigOverrides(Map<String, String> configOverrides) {
            this.configOverrides = configOverrides;
        }
    }

    /** Where the rendered output for this map is stored. */
    public static class Storage {

        /** "auto" makes the reconciler provision/reuse the tenant's bucket claim. */
        private String bucketClaim = "auto";

        private String prefix;

        public String getBucketClaim() {
            return bucketClaim;
        }

        public void setBucketClaim(String bucketClaim) {
            this.bucketClaim = bucketClaim;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }
    }

    /** Resource requests/limits applied to the render job pod. */
    public static class Resources {
        private String cpu;
        private String memory;

        public String getCpu() {
            return cpu;
        }

        public void setCpu(String cpu) {
            this.cpu = cpu;
        }

        public String getMemory() {
            return memory;
        }

        public void setMemory(String memory) {
            this.memory = memory;
        }
    }
}
