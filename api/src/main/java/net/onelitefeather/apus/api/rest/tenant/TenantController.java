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
package net.onelitefeather.apus.api.rest.tenant;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Patch;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import java.util.List;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.security.ApusPrincipal;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.Tenant;
import net.onelitefeather.apus.operator.api.TenantSpec;

/**
 * {@code GET /api/tenants}, {@code POST /api/tenants}, and {@code PATCH /api/tenants/{name}} --
 * platform-level, {@code platform-admin} only (design spec §10.3, §11.1). Unlike every other
 * controller in {@code rest/}, this one never calls {@code TenantResolver}: {@code Tenant} is
 * cluster-scoped, and a
 * platform-admin's reach here is deliberately cluster-wide, not confined to a single namespace
 * -- see {@code TenantResolverTest#namespaceForRejectsAPlatformAdminWithoutATenantToo}'s Javadoc
 * from task 1, which is exactly the boundary this controller sits on the other side of.
 *
 * <p>{@code @Secured(IS_AUTHENTICATED)} only enforces the deny-by-default baseline (no anonymous
 * access); the {@code platform-admin} role gate itself is a manual check against {@link
 * ApusPrincipal#isPlatformAdmin()} in each method, not a role string on the annotation --
 * <a href="https://docs.micronaut.io">Micronaut's</a> {@code @Secured} role matching happens via
 * an AOP interceptor that only runs inside a live IoC container, and with no {@code
 * micronaut-test-junit5}/HTTP-client dependency on this module's test classpath (see
 * task-1-report.md's "Concerns" section), a unit test that instantiates this controller directly
 * cannot exercise that interceptor at all. A manual check keeps the "insufficient role -> 403"
 * behaviour testable the same way as everything else in this module.
 */
@Controller("/api/tenants")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class TenantController {

    private final TenantRepository repository;
    private final PrincipalResolver principalResolver;

    public TenantController(TenantRepository repository, PrincipalResolver principalResolver) {
        this.repository = repository;
        this.principalResolver = principalResolver;
    }

    @Get
    public HttpResponse<List<TenantResponse>> list(Authentication authentication) {
        requirePlatformAdmin(authentication);
        List<TenantResponse> tenants =
                repository.list().stream().map(TenantResponse::from).toList();
        return HttpResponse.ok(tenants);
    }

    @Post
    public HttpResponse<TenantResponse> create(Authentication authentication, @Body CreateTenantRequest request) {
        requirePlatformAdmin(authentication);
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name must not be blank");
        }

        Tenant tenant = new Tenant();
        tenant.getMetadata().setName(request.name());
        TenantSpec spec = tenant.getSpec();
        spec.setDisplayName(request.displayName());
        if (request.storageQuota() != null) {
            spec.getStorage().setQuota(request.storageQuota());
        }
        if (request.maxObjects() != null) {
            spec.getStorage().setMaxObjects(request.maxObjects());
        }
        if (request.allowedHostingDomains() != null) {
            spec.getHosting().setAllowedDomains(request.allowedHostingDomains());
        }

        Tenant created = repository.create(tenant);
        return HttpResponse.created(TenantResponse.from(created));
    }

    /**
     * Changes an existing tenant's storage quota and/or allowed hosting domains (design spec
     * §10.3: {@code platform-admin} may "create/modify/delete tenants, quotas"). {@code name}
     * comes from the path, exactly like every other tenant-identifying value in this module --
     * never re-derived from the body. Closes the gap the platform dashboard flagged: before this,
     * a quota was only settable at {@link #create}-time.
     */
    @Patch("/{name}")
    public HttpResponse<TenantResponse> update(
            Authentication authentication, @PathVariable String name, @Body UpdateTenantRequest request) {
        requirePlatformAdmin(authentication);
        Tenant tenant = repository.findByName(name).orElseThrow(() -> new NotFoundException("no tenant '" + name + "'"));

        TenantSpec spec = tenant.getSpec();
        if (request.storageQuota() != null) {
            spec.getStorage().setQuota(request.storageQuota());
        }
        if (request.maxObjects() != null) {
            spec.getStorage().setMaxObjects(request.maxObjects());
        }
        if (request.allowedHostingDomains() != null) {
            spec.getHosting().setAllowedDomains(request.allowedHostingDomains());
        }

        Tenant updated = repository.update(tenant);
        return HttpResponse.ok(TenantResponse.from(updated));
    }

    private ApusPrincipal requirePlatformAdmin(Authentication authentication) {
        ApusPrincipal principal = principalResolver.resolve(authentication);
        if (!principal.isPlatformAdmin()) {
            throw new ForbiddenException(
                    "principal '" + principal.subject() + "' is not a platform-admin");
        }
        return principal;
    }
}
