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
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantUiConfigTest {

    @Test
    void isDisabledWhenNoHostIsConfigured() {
        assertFalse(TenantUiConfig.fromEnvironment(name -> null).enabled());
        assertEquals(TenantUiConfig.disabled(), TenantUiConfig.fromEnvironment(name -> null));
    }

    @Test
    void isDisabledWhenTheHostIsBlank() {
        Map<String, String> env = Map.of("APUS_TENANT_UI_HOST", "   ");

        assertFalse(TenantUiConfig.fromEnvironment(env::get).enabled());
    }

    @Test
    void isEnabledOnceAHostIsSet() {
        Map<String, String> env = Map.of("APUS_TENANT_UI_HOST", "apus.example.dev");

        assertTrue(TenantUiConfig.fromEnvironment(env::get).enabled());
    }

    @Test
    void readsEveryVariable() {
        Map<String, String> env = Map.ofEntries(
                Map.entry("APUS_TENANT_UI_HOST", "apus.example.dev"),
                Map.entry("APUS_TENANT_UI_IMAGE", "apus/ui:1.2.3"),
                Map.entry("APUS_TENANT_UI_INGRESS_CLASS", "cloudflare-tunnel"),
                Map.entry("APUS_TENANT_UI_API_BASE_URL", "https://apus.example.dev"),
                Map.entry("APUS_TENANT_UI_OIDC_ISSUER", "https://issuer.example/v2.0"),
                Map.entry("APUS_TENANT_UI_OIDC_CLIENT_ID", "client-id"),
                Map.entry("APUS_TENANT_UI_OIDC_SCOPE", "api://client-id/access_as_user openid"));

        TenantUiConfig config = TenantUiConfig.fromEnvironment(env::get);

        assertEquals("apus.example.dev", config.host());
        assertEquals("apus/ui:1.2.3", config.image());
        assertEquals("cloudflare-tunnel", config.ingressClassName());
        assertEquals("https://apus.example.dev", config.apiBaseUrl());
        assertEquals("https://issuer.example/v2.0", config.oidcIssuer());
        assertEquals("client-id", config.oidcClientId());
        assertEquals("api://client-id/access_as_user openid", config.oidcScope());
    }

    @Test
    void fallsBackToTheDefaultImageAndIngressClass() {
        Map<String, String> env = Map.of("APUS_TENANT_UI_HOST", "apus.example.dev");

        TenantUiConfig config = TenantUiConfig.fromEnvironment(env::get);

        assertEquals("apus/ui:dev", config.image());
        assertEquals("nginx", config.ingressClassName());
    }
}
