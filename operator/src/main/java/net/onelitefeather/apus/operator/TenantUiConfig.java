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

import java.util.function.Function;

/**
 * Settings for the per-tenant application instance {@code TenantReconciler} provisions: which
 * image to run it from, which host and ingress class to expose it on, and the public runtime
 * configuration every instance is handed.
 *
 * <p>The four {@code NUXT_PUBLIC_*} values are modelled one by one rather than as a free-form
 * map: {@link OperatorConfig} is built entirely from environment variables, so a map would have
 * to be serialised through a single variable and would lose its schema, its documentation and the
 * ability to test each value on its own. All four are identical for every tenant -- same API,
 * same issuer, same OIDC client -- and none of them is a secret; every one ends up in the served
 * HTML by design.
 *
 * @param host the host tenant paths hang off. <b>Blank disables the feature entirely</b>, and
 *     blank is the default: an instance with no host would have nothing to serve it, and creating
 *     a Deployment nobody can reach would burn a pod per tenant for nothing
 * @param image the tenant application image -- the same one the platform chart deploys as its
 *     own {@code ui}, since one image serves any prefix (see {@code
 *     net.onelitefeather.apus.operator.tenant.TenantUiResourceBuilder})
 * @param ingressClassName the ingress class of the per-tenant {@code Ingress}. Must match the
 *     platform ingress's class: both serve paths on {@link #host}
 * @param apiBaseUrl becomes {@code NUXT_PUBLIC_API_BASE_URL}. The origin only, with no {@code
 *     /api} suffix -- every method of the typed client already asks for a path beginning with
 *     {@code /api}, so a suffix here produces {@code /api/api/tenants} and a bare 403
 * @param oidcIssuer becomes {@code NUXT_PUBLIC_OIDC_ISSUER}
 * @param oidcClientId becomes {@code NUXT_PUBLIC_OIDC_CLIENT_ID}
 * @param oidcScope becomes {@code NUXT_PUBLIC_OIDC_SCOPE}
 */
public record TenantUiConfig(
        String host,
        String image,
        String ingressClassName,
        String apiBaseUrl,
        String oidcIssuer,
        String oidcClientId,
        String oidcScope) {

    private static final String DEFAULT_IMAGE = "apus/ui:dev";
    private static final String DEFAULT_INGRESS_CLASS = "nginx";

    /** The feature switched off: no host, so no per-tenant instance is provisioned at all. */
    public static TenantUiConfig disabled() {
        return new TenantUiConfig("", DEFAULT_IMAGE, DEFAULT_INGRESS_CLASS, "", "", "", "");
    }

    /**
     * Builds the settings from environment variables, mirroring {@link
     * OperatorConfig#fromEnvironment(Function)} -- including taking a {@code Function} rather than
     * reading {@link System#getenv()} directly, so tests supply a fake environment instead of
     * mutating the real one.
     *
     * <p>Recognised variables: {@code APUS_TENANT_UI_HOST}, {@code APUS_TENANT_UI_IMAGE}, {@code
     * APUS_TENANT_UI_INGRESS_CLASS}, {@code APUS_TENANT_UI_API_BASE_URL}, {@code
     * APUS_TENANT_UI_OIDC_ISSUER}, {@code APUS_TENANT_UI_OIDC_CLIENT_ID}, {@code
     * APUS_TENANT_UI_OIDC_SCOPE}.
     */
    public static TenantUiConfig fromEnvironment(Function<String, String> env) {
        return new TenantUiConfig(
                valueOrDefault(env.apply("APUS_TENANT_UI_HOST"), ""),
                valueOrDefault(env.apply("APUS_TENANT_UI_IMAGE"), DEFAULT_IMAGE),
                valueOrDefault(env.apply("APUS_TENANT_UI_INGRESS_CLASS"), DEFAULT_INGRESS_CLASS),
                valueOrDefault(env.apply("APUS_TENANT_UI_API_BASE_URL"), ""),
                valueOrDefault(env.apply("APUS_TENANT_UI_OIDC_ISSUER"), ""),
                valueOrDefault(env.apply("APUS_TENANT_UI_OIDC_CLIENT_ID"), ""),
                valueOrDefault(env.apply("APUS_TENANT_UI_OIDC_SCOPE"), ""));
    }

    /** Whether a per-tenant instance should be provisioned at all -- see {@link #host}. */
    public boolean enabled() {
        return host != null && !host.isBlank();
    }

    private static String valueOrDefault(String value, String defaultValue) {
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
