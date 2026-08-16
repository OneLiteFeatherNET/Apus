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
package net.onelitefeather.apus.api.directory;

import io.micronaut.context.annotation.ConfigurationProperties;

/**
 * Credentials for the confidential app registration the directory operations run as.
 *
 * <p><b>Not the app registration the browser uses.</b> That one is a SPA -- a public client,
 * which cannot hold a secret and cannot use the client-credentials flow at all, so Graph
 * application permissions granted there would be unusable and would suggest the browser held
 * them. This is a second, confidential registration used only by this module, server-side.
 *
 * <p>{@link #getClientSecret()} arrives from a Kubernetes {@code Secret} via {@code secretKeyRef}
 * and is never inlined into a manifest. A client secret rather than workload identity federation
 * because federation is not available here: this cluster's OIDC issuer is an internal address
 * Entra cannot reach to fetch signing keys.
 *
 * <p>All three values are empty by default, which switches the directory off entirely -- see
 * {@link DirectoryFactory}. A platform that has not granted the permissions gets an API that
 * says so, rather than one that fails to start.
 */
@ConfigurationProperties("apus.directory")
public class GraphDirectoryConfiguration {

    private String tenantId = "";
    private String clientId = "";
    private String clientSecret = "";
    private String authority = "https://login.microsoftonline.com";
    private String graphEndpoint = "https://graph.microsoft.com/v1.0";

    /** Whether enough is configured to talk to the directory at all. */
    public boolean isConfigured() {
        return !tenantId.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
    }

    public String getTenantId() {
        return tenantId;
    }

    public void setTenantId(String tenantId) {
        this.tenantId = tenantId == null ? "" : tenantId.trim();
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? "" : clientId.trim();
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
    }

    public String getAuthority() {
        return authority;
    }

    public void setAuthority(String authority) {
        this.authority = authority == null || authority.isBlank() ? "https://login.microsoftonline.com" : authority;
    }

    public String getGraphEndpoint() {
        return graphEndpoint;
    }

    public void setGraphEndpoint(String graphEndpoint) {
        this.graphEndpoint =
                graphEndpoint == null || graphEndpoint.isBlank() ? "https://graph.microsoft.com/v1.0" : graphEndpoint;
    }
}
