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
package net.onelitefeather.apus.operator.hosting;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import io.fabric8.kubernetes.api.model.Namespaced;
import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Version;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * cert-manager's {@code Certificate}, modelled with only the fields {@link HostingResourceBuilder}
 * needs to request a TLS certificate for a {@code BlueMapHosting}'s ingress.
 *
 * <p>Apus does not run its own certificate authority: creating one of these makes cert-manager
 * issue a certificate and write it into the named {@code Secret}, which the ingress then
 * references via {@code spec.tls[].secretName}. This class is a client-side model of a CRD
 * cert-manager owns -- it must never be fed to Apus's own CRD generator, which is why it lives
 * in this package rather than {@code net.onelitefeather.apus.operator.api} (the only package the
 * generator scans, see {@code CrdGeneratorMain}). Shipping a {@code cert-manager.io} CRD of our
 * own would fight with cert-manager's, exactly the failure {@code
 * net.onelitefeather.apus.operator.rook.ObjectBucketClaim} already avoids for Rook's CRDs.
 *
 * <p>Kept as a single file with nested spec/status types (unlike the three-file Rook model
 * classes) because Apus only ever sets three leaf fields on this resource -- a dedicated
 * top-level {@code CertificateSpec}/{@code CertificateStatus} pair would be pure ceremony here.
 */
@Group("cert-manager.io")
@Version("v1")
@Kind("Certificate")
@Plural("certificates")
public class Certificate extends CustomResource<Certificate.CertificateSpec, Certificate.CertificateStatus>
        implements Namespaced {

    @Override
    protected CertificateSpec initSpec() {
        return new CertificateSpec();
    }

    @Override
    protected CertificateStatus initStatus() {
        return new CertificateStatus();
    }

    /** Desired state of a cert-manager {@code Certificate}. Plain data, no Kubernetes access. */
    public static class CertificateSpec {

        /** Name of the {@code Secret} cert-manager writes the issued certificate/key into. */
        private String secretName;

        private List<String> dnsNames = new ArrayList<>();
        private IssuerRef issuerRef = new IssuerRef();

        public String getSecretName() {
            return secretName;
        }

        public void setSecretName(String secretName) {
            this.secretName = secretName;
        }

        public List<String> getDnsNames() {
            return dnsNames;
        }

        public void setDnsNames(List<String> dnsNames) {
            this.dnsNames = dnsNames;
        }

        public IssuerRef getIssuerRef() {
            return issuerRef;
        }

        public void setIssuerRef(IssuerRef issuerRef) {
            this.issuerRef = issuerRef;
        }

        /** Which cert-manager issuer signs this certificate. */
        public static class IssuerRef {
            private String name;
            private String kind = "ClusterIssuer";
            private String group = "cert-manager.io";

            public String getName() {
                return name;
            }

            public void setName(String name) {
                this.name = name;
            }

            public String getKind() {
                return kind;
            }

            public void setKind(String kind) {
                this.kind = kind;
            }

            public String getGroup() {
                return group;
            }

            public void setGroup(String group) {
                this.group = group;
            }
        }
    }

    /**
     * Observed state of a cert-manager {@code Certificate}. Apus never reads this back (the
     * {@code BlueMapHostingReconciler} determines TLS readiness from the ingress, per the phase 3
     * plan) -- kept present rather than omitted so the type still matches cert-manager's actual
     * shape and {@link CustomResource} has a status to initialise.
     *
     * <p>Modelled as an open bag of properties ({@code additionalProperties}, the same {@code
     * @JsonAnyGetter}/{@code @JsonAnySetter} pattern every fabric8-generated model class uses for
     * "no fields Apus cares about yet") rather than a genuinely empty class: with zero declared
     * fields, fabric8's Jackson mapper (which runs with {@code FAIL_ON_EMPTY_BEANS} enabled)
     * throws {@code InvalidDefinitionException} the moment a {@link Certificate} is actually sent
     * to an API server -- only caught once {@code BlueMapHostingReconciler} started doing that for
     * real; {@link HostingResourceBuilder#certificate} alone never serialises anything.
     */
    public static class CertificateStatus {

        private Map<String, Object> additionalProperties = new LinkedHashMap<>();

        @JsonAnyGetter
        public Map<String, Object> getAdditionalProperties() {
            return additionalProperties;
        }

        @JsonAnySetter
        public void setAdditionalProperty(String name, Object value) {
            additionalProperties.put(name, value);
        }
    }
}
