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
package net.onelitefeather.apus.operator.tenant;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.PodSecurityContext;
import io.fabric8.kubernetes.api.model.PodSecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.SecurityContextBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPathBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackendBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackendBuilder;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.operator.TenantUiConfig;
import net.onelitefeather.apus.operator.api.Tenant;

/**
 * Turns a {@link Tenant} into the Kubernetes objects that serve it its own instance of the tenant
 * application at {@code https://<host>/t/<tenant>/}: a {@link Deployment}, a {@link Service} and
 * an {@link Ingress}.
 *
 * <p>Pure function -- no Kubernetes client, no side effects -- following the same shape as {@code
 * net.onelitefeather.apus.operator.hosting.HostingResourceBuilder}. Labels and the owner reference
 * are passed in rather than rebuilt here, so what lands on these three objects cannot drift from
 * what {@link TenantReconciler} stamps on the namespace, the quota and the limit range.
 *
 * <p><b>One image serves every tenant.</b> {@code NUXT_APP_BASE_URL} moves the served prefix at
 * runtime (verified against the built image: started with {@code /t/acme/} the Nitro server serves
 * its shell and deep links under that prefix, emits assets at {@code /t/acme/_nuxt/…}, and 404s on
 * the bare root). A tenant instance therefore differs from the platform's own {@code ui}
 * Deployment in exactly one environment variable, not in a per-tenant build.
 */
public final class TenantUiResourceBuilder {

    /** Name shared by the Deployment, the Service and the Ingress in a tenant's own namespace. */
    public static final String RESOURCE_NAME = "apus-tenant-ui";

    /**
     * The port the image listens on, pinned by {@code ui/Dockerfile} ({@code PORT=8080}). Nitro's
     * own default is 3000 and is not what this image uses.
     */
    public static final int CONTAINER_PORT = 8080;

    private static final String CONTAINER_NAME = "ui";

    private static final String PORT_NAME = "http";

    /**
     * Requests are not optional here, and this is the one mistake in this class that would not
     * show up in any test against a mock API server. A tenant namespace carries a {@code
     * ResourceQuota} on {@code requests.cpu}/{@code requests.memory} and a {@code LimitRange} with
     * no spec at all, so the quota makes both requests mandatory and nothing supplies a default.
     * A Deployment without them is accepted and then never produces a pod.
     *
     * <p>The values match {@code ui.resources} in the platform chart, where they were measured
     * against Nitro's actual shell-per-request profile rather than guessed.
     */
    private static final String CPU_REQUEST = "50m";

    private static final String MEMORY_REQUEST = "128Mi";

    private static final String MEMORY_LIMIT = "256Mi";

    private TenantUiResourceBuilder() {}

    /** The prefix this tenant's instance is served under, with the trailing slash Nuxt expects. */
    public static String basePath(Tenant tenant) {
        return "/t/" + tenant.getMetadata().getName() + "/";
    }

    /** The same prefix as an ingress path, which carries no trailing slash. */
    public static String ingressPath(Tenant tenant) {
        return "/t/" + tenant.getMetadata().getName();
    }

    /**
     * The two redirect URIs the identity provider must have registered before anyone can sign in
     * to this tenant's instance.
     *
     * <p>A wildcard is not an option even where the provider allows one: Entra strips the query
     * string when a wildcard URI matches, and the authorization code lives in that query string.
     * Nor can the operator register these itself -- that needs Microsoft Graph application
     * permissions on the app registration, a grant that belongs to a different subsystem. So they
     * are reported instead, on {@code Tenant.status.redirectUris}, because a missing registration
     * fails at sign-in with {@code AADSTS50011} from the broker and leaves nothing at all in this
     * cluster's logs to find.
     */
    public static List<String> redirectUris(Tenant tenant, TenantUiConfig config) {
        String prefix = "https://" + config.host() + basePath(tenant);
        return List.of(prefix + "auth/callback", prefix + "auth/silent-renew");
    }

    /**
     * Builds the {@link Deployment} running this tenant's instance.
     *
     * @param tenant the tenant whose instance this is; supplies the namespace and the base path
     * @param config the image and the public runtime configuration every instance is handed
     * @param labels the tenant-ownership labels, also used as the pod selector -- passed in so
     *     they cannot drift from what {@link TenantReconciler} stamps elsewhere
     * @param owner the owning {@link Tenant}, so deleting it takes this with it
     * @return the manifest, not yet submitted to the API server
     */
    public static Deployment deployment(
            Tenant tenant, TenantUiConfig config, Map<String, String> labels, OwnerReference owner) {
        Container container = new ContainerBuilder()
                .withName(CONTAINER_NAME)
                .withImage(config.image())
                .withPorts(containerPort())
                .withEnv(env(tenant, config))
                .withResources(resources())
                .withSecurityContext(containerSecurityContext())
                .withReadinessProbe(probe(tenant))
                .withLivenessProbe(probe(tenant))
                .build();

        return new DeploymentBuilder()
                .withNewMetadata()
                .withName(RESOURCE_NAME)
                .withNamespace(TenantReconciler.namespaceFor(tenant))
                .withLabels(labels)
                .withOwnerReferences(owner)
                .endMetadata()
                .withNewSpec()
                .withReplicas(1)
                .withNewSelector()
                .withMatchLabels(labels)
                .endSelector()
                .withNewTemplate()
                .withNewMetadata()
                .withLabels(labels)
                .endMetadata()
                .withNewSpec()
                .withContainers(container)
                .withSecurityContext(podSecurityContext())
                .endSpec()
                .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * Builds the {@link Service} fronting this tenant's instance.
     *
     * @param tenant the tenant whose instance this is; supplies the namespace
     * @param labels the tenant-ownership labels, also used as the pod selector
     * @param owner the owning {@link Tenant}
     * @return the manifest, not yet submitted to the API server
     */
    public static Service service(Tenant tenant, Map<String, String> labels, OwnerReference owner) {
        ServicePort port = new ServicePortBuilder()
                .withName(PORT_NAME)
                .withPort(CONTAINER_PORT)
                .withNewTargetPort(CONTAINER_PORT)
                .build();

        return new ServiceBuilder()
                .withNewMetadata()
                .withName(RESOURCE_NAME)
                .withNamespace(TenantReconciler.namespaceFor(tenant))
                .withLabels(labels)
                .withOwnerReferences(owner)
                .endMetadata()
                .withNewSpec()
                .withSelector(labels)
                .withPorts(port)
                .endSpec()
                .build();
    }

    /**
     * Builds the {@link Ingress} exposing this tenant's instance at {@code <host>/t/<tenant>}.
     *
     * <p><b>It has to be a per-tenant object, and it has to live in the tenant's namespace.</b>
     * An Ingress may only reference a Service in its own namespace, and each tenant's Service is
     * in {@code bluemap-<name>} -- so a single operator-owned Ingress listing every tenant's path
     * is not available at any price. That it is also garbage-collected with the tenant, and keeps
     * the platform chart's ingress a static file rather than something a controller writes to, is
     * a bonus rather than the reason.
     *
     * <p>Path ordering against the platform's own {@code /}, {@code /api} and {@code /console} is
     * not this object's problem to solve: the cluster's tunnel controller flattens every Ingress
     * into one rule list and sorts it by path length descending before appending its 404
     * catch-all, so {@code /t/<tenant>} lands ahead of {@code /} on its own.
     *
     * <p>No annotations and no {@code tls} section: the tunnel controller already defaults
     * {@code backend-protocol} to {@code http}, and TLS terminates at the edge, so a {@code tls}
     * section here would ask for a certificate nobody issues.
     *
     * @param tenant the tenant whose instance this is; supplies the namespace and the path
     * @param config the host and ingress class to expose it on
     * @param labels the tenant-ownership labels
     * @param owner the owning {@link Tenant}
     * @return the manifest, not yet submitted to the API server
     */
    public static Ingress ingress(
            Tenant tenant, TenantUiConfig config, Map<String, String> labels, OwnerReference owner) {
        var backend = new IngressBackendBuilder()
                .withService(new IngressServiceBackendBuilder()
                        .withName(RESOURCE_NAME)
                        .withNewPort()
                        .withName(PORT_NAME)
                        .endPort()
                        .build())
                .build();

        var path = new HTTPIngressPathBuilder()
                .withPath(ingressPath(tenant))
                .withPathType("Prefix")
                .withBackend(backend)
                .build();

        var rule = new IngressRuleBuilder()
                .withHost(config.host())
                .withNewHttp()
                .withPaths(path)
                .endHttp()
                .build();

        return new IngressBuilder()
                .withNewMetadata()
                .withName(RESOURCE_NAME)
                .withNamespace(TenantReconciler.namespaceFor(tenant))
                .withLabels(labels)
                .withOwnerReferences(owner)
                .endMetadata()
                .withNewSpec()
                .withIngressClassName(config.ingressClassName())
                .withRules(rule)
                .endSpec()
                .build();
    }

    /**
     * The instance's whole configuration. Every value is public by design -- this is a public OIDC
     * client and all of it ends up in the served HTML -- so none of it comes from a Secret.
     * {@code NUXT_APP_BASE_URL} is the only one that differs between tenants.
     */
    private static List<EnvVar> env(Tenant tenant, TenantUiConfig config) {
        return List.of(
                literal("NUXT_APP_BASE_URL", basePath(tenant)),
                literal("NUXT_PUBLIC_API_BASE_URL", config.apiBaseUrl()),
                literal("NUXT_PUBLIC_OIDC_ISSUER", config.oidcIssuer()),
                literal("NUXT_PUBLIC_OIDC_CLIENT_ID", config.oidcClientId()),
                literal("NUXT_PUBLIC_OIDC_SCOPE", config.oidcScope()));
    }

    private static EnvVar literal(String name, String value) {
        return new EnvVarBuilder().withName(name).withValue(value).build();
    }

    private static ContainerPort containerPort() {
        return new ContainerPortBuilder()
                .withName(PORT_NAME)
                .withContainerPort(CONTAINER_PORT)
                .build();
    }

    /**
     * Both probes hit the tenant's own base path rather than {@code /}: with {@code
     * NUXT_APP_BASE_URL} set, the bare root 404s, so a probe there would restart a perfectly
     * healthy pod forever -- and would only start doing it once the feature was switched on in a
     * real cluster.
     */
    private static Probe probe(Tenant tenant) {
        return new ProbeBuilder()
                .withNewHttpGet()
                .withPath(basePath(tenant))
                .withNewPort(CONTAINER_PORT)
                .endHttpGet()
                .withInitialDelaySeconds(5)
                .withPeriodSeconds(10)
                .build();
    }

    /**
     * The same hardening the platform chart applies to its own {@code ui} Deployment, which runs
     * this identical image. Not optional and not configurable: an instance created by a
     * controller must not end up less restricted than the same software installed by Helm, and
     * these four settings are exactly what {@code PodSecurity "restricted"} asks for -- a warning
     * on every tenant pod today, and a rejection the moment a platform sets tenant namespaces to
     * enforce it.
     */
    private static PodSecurityContext podSecurityContext() {
        return new PodSecurityContextBuilder()
                .withRunAsNonRoot(true)
                // The distroless :nonroot base runs as 65532, unlike the Java images' 10001.
                .withRunAsUser(65532L)
                .withNewSeccompProfile()
                .withType("RuntimeDefault")
                .endSeccompProfile()
                .build();
    }

    /** See {@link #podSecurityContext()}. The Nitro server only ever reads from the image. */
    private static SecurityContext containerSecurityContext() {
        return new SecurityContextBuilder()
                .withAllowPrivilegeEscalation(false)
                .withReadOnlyRootFilesystem(true)
                .withNewCapabilities()
                .withDrop("ALL")
                .endCapabilities()
                .build();
    }

    /** See {@link #CPU_REQUEST}: without these the namespace's quota rejects every pod. */
    private static ResourceRequirements resources() {
        return new ResourceRequirementsBuilder()
                .withRequests(Map.of("cpu", new Quantity(CPU_REQUEST), "memory", new Quantity(MEMORY_REQUEST)))
                .withLimits(Map.of("memory", new Quantity(MEMORY_LIMIT)))
                .build();
    }
}
