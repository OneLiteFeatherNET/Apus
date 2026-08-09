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
 * Desired state of a {@link BlueMapHosting}. Plain data, no Kubernetes access.
 *
 * <p>Every group is initialised in its field declaration so a reconciler (or a test) never has
 * to null-check its way down to a leaf field.
 */
public class BlueMapHostingSpec {

    /** The {@link BlueMapMap}s this webserver displays, in the same namespace as this resource. */
    private List<Ref> maps = new ArrayList<>();

    private String hostname;
    private String ingressClassName = "nginx";
    private Tls tls = new Tls();
    private int replicas = 1;
    private Resources resources = new Resources();

    public List<Ref> getMaps() {
        return maps;
    }

    public void setMaps(List<Ref> maps) {
        this.maps = maps;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public String getIngressClassName() {
        return ingressClassName;
    }

    public void setIngressClassName(String ingressClassName) {
        this.ingressClassName = ingressClassName;
    }

    public Tls getTls() {
        return tls;
    }

    public void setTls(Tls tls) {
        this.tls = tls;
    }

    public int getReplicas() {
        return replicas;
    }

    public void setReplicas(int replicas) {
        this.replicas = replicas;
    }

    public Resources getResources() {
        return resources;
    }

    public void setResources(Resources resources) {
        this.resources = resources;
    }

    /** TLS termination for the ingress fronting this webserver. */
    public static class Tls {
        private Ref issuerRef = new Ref();
        private String issuerKind = "ClusterIssuer";
        private boolean enabled = true;

        public Ref getIssuerRef() {
            return issuerRef;
        }

        public void setIssuerRef(Ref issuerRef) {
            this.issuerRef = issuerRef;
        }

        public String getIssuerKind() {
            return issuerKind;
        }

        public void setIssuerKind(String issuerKind) {
            this.issuerKind = issuerKind;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /** Resource requests/limits applied to the webserver pod. */
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
