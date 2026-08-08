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

/**
 * Desired state of a {@link WorldSource}. Plain data, no Kubernetes access.
 *
 * <p>Every group is initialised in its field declaration so a reconciler (or a test) never has
 * to null-check its way down to a leaf field.
 */
public class WorldSourceSpec {

    /** "s3" | "pterodactyl" | "upload" | "push" */
    private String type;

    private S3Source s3 = new S3Source();
    private Pterodactyl pterodactyl = new Pterodactyl();

    /** Cron expression driving polling for pull-based source types; null means manual only. */
    private String poll;

    private List<WorldSource.WorldSelector> worlds = new ArrayList<>();
    private Retention retention = new Retention();

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public S3Source getS3() {
        return s3;
    }

    public void setS3(S3Source s3) {
        this.s3 = s3;
    }

    public Pterodactyl getPterodactyl() {
        return pterodactyl;
    }

    public void setPterodactyl(Pterodactyl pterodactyl) {
        this.pterodactyl = pterodactyl;
    }

    public String getPoll() {
        return poll;
    }

    public void setPoll(String poll) {
        this.poll = poll;
    }

    public List<WorldSource.WorldSelector> getWorlds() {
        return worlds;
    }

    public void setWorlds(List<WorldSource.WorldSelector> worlds) {
        this.worlds = worlds;
    }

    public Retention getRetention() {
        return retention;
    }

    public void setRetention(Retention retention) {
        this.retention = retention;
    }

    /** Connection details for an S3-compatible bucket backing this source. */
    public static class S3Source {
        private String endpoint;
        private String bucket;
        private String prefix;
        private Ref credentialsSecretRef = new Ref();

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public Ref getCredentialsSecretRef() {
            return credentialsSecretRef;
        }

        public void setCredentialsSecretRef(Ref credentialsSecretRef) {
            this.credentialsSecretRef = credentialsSecretRef;
        }
    }

    /** Connection details for a Pterodactyl panel backing this source. */
    public static class Pterodactyl {
        private String panelUrl;
        private String serverId;
        private Ref credentialsSecretRef = new Ref();

        /** "latest" makes the ingest job pick the most recent backup/world archive itself. */
        private String select = "latest";

        public String getPanelUrl() {
            return panelUrl;
        }

        public void setPanelUrl(String panelUrl) {
            this.panelUrl = panelUrl;
        }

        public String getServerId() {
            return serverId;
        }

        public void setServerId(String serverId) {
            this.serverId = serverId;
        }

        public Ref getCredentialsSecretRef() {
            return credentialsSecretRef;
        }

        public void setCredentialsSecretRef(Ref credentialsSecretRef) {
            this.credentialsSecretRef = credentialsSecretRef;
        }

        public String getSelect() {
            return select;
        }

        public void setSelect(String select) {
            this.select = select;
        }
    }

    /** How many past bundle versions this source retains before older ones are pruned. */
    public static class Retention {
        private int keepVersions = 5;

        public int getKeepVersions() {
            return keepVersions;
        }

        public void setKeepVersions(int keepVersions) {
            this.keepVersions = keepVersions;
        }
    }
}
