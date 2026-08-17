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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.Test;

/**
 * {@code TenantController} is platform-scoped ({@code Tenant} is cluster-scoped, design spec
 * §8.1), so unlike the tenant-scoped controllers there is no "foreign tenant -> 404" case here:
 * a {@code platform-admin} legitimately sees every tenant, by design (§10.3 "clusterweite
 * Sicht"). What replaces it: insufficient role (not a platform-admin) must produce 403.
 */
class TenantControllerTest {

    private final InMemoryTenantRepository repository = new InMemoryTenantRepository();
    private final TenantController controller = new TenantController(repository, new PrincipalResolver());

    private static Authentication platformAdmin() {
        return Authentication.build("root", List.of("platform-admin"), java.util.Map.of());
    }

    private static Authentication tenantOwner() {
        return Authentication.build(
                "alice", List.of("tenant-owner"), java.util.Map.of(PrincipalResolver.TENANT_CLAIM, "acme"));
    }

    @Test
    void listReturnsAllTenantsForAPlatformAdmin() {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName("acme");
        tenant.getSpec().setDisplayName("Acme Corp");
        repository.put(tenant);

        var response = controller.list(platformAdmin());

        assertEquals(200, response.getStatus().getCode());
        assertEquals(1, response.body().size());
        assertEquals("acme", response.body().get(0).name());
    }

    @Test
    void listRejectsANonPlatformAdmin() {
        assertThrows(ForbiddenException.class, () -> controller.list(tenantOwner()));
    }

    @Test
    void createAddsANewTenantForAPlatformAdmin() {
        var request = new CreateTenantRequest("globex", "Globex", "200Gi", 1_000_000L, List.of("*.globex.example.net"));

        var response = controller.create(platformAdmin(), request);

        assertEquals(201, response.getStatus().getCode());
        assertEquals("globex", response.body().name());
        assertEquals("Globex", response.body().displayName());
        assertEquals("200Gi", response.body().storage().quota());
        assertTrue(repository.findByName("globex").isPresent());
    }

    @Test
    void createRejectsANonPlatformAdmin() {
        var request = new CreateTenantRequest("globex", "Globex", null, null, List.of());
        assertThrows(ForbiddenException.class, () -> controller.create(tenantOwner(), request));
    }

    @Test
    void createRejectsABlankName() {
        var request = new CreateTenantRequest(" ", "Globex", null, null, List.of());
        assertThrows(BadRequestException.class, () -> controller.create(platformAdmin(), request));
    }

    @Test
    void updateChangesQuotaAndAllowedDomainsForAPlatformAdmin() {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName("acme");
        tenant.getSpec().setDisplayName("Acme Corp");
        tenant.getSpec().getStorage().setQuota("100Gi");
        repository.put(tenant);

        var request = new UpdateTenantRequest("500Gi", 42_000L, List.of("*.acme.example.net"), null);
        var response = controller.update(platformAdmin(), "acme", request);

        assertEquals(200, response.getStatus().getCode());
        assertEquals("500Gi", response.body().storage().quota());
        assertEquals(42_000L, response.body().storage().maxObjects());
        assertEquals(List.of("*.acme.example.net"), response.body().allowedHostingDomains());
        // displayName is untouched -- this endpoint only ever changes quota/domains.
        assertEquals("Acme Corp", response.body().displayName());
    }

    @Test
    void updateLeavesAFieldUntouchedWhenItsRequestValueIsNull() {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName("acme");
        tenant.getSpec().getStorage().setQuota("100Gi");
        tenant.getSpec().getHosting().setAllowedDomains(List.of("maps.acme.example.net"));
        repository.put(tenant);

        var request = new UpdateTenantRequest(null, null, null, null);
        var response = controller.update(platformAdmin(), "acme", request);

        assertEquals("100Gi", response.body().storage().quota());
        assertEquals(List.of("maps.acme.example.net"), response.body().allowedHostingDomains());
    }

    @Test
    void updateRejectsANonPlatformAdmin() {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName("acme");
        repository.put(tenant);

        var request = new UpdateTenantRequest("500Gi", null, null, null);
        assertThrows(ForbiddenException.class, () -> controller.update(tenantOwner(), "acme", request));
    }

    @Test
    void updateRejectsAnUnknownTenant() {
        var request = new UpdateTenantRequest("500Gi", null, null, null);
        assertThrows(NotFoundException.class, () -> controller.update(platformAdmin(), "does-not-exist", request));
    }

    /**
     * The operator reports the redirect URIs a tenant's own application instance needs, because
     * it cannot register them with the identity provider itself. They have to reach the console,
     * or the person who just created a tenant walks away without being told what remains -- and
     * the failure they eventually hit (AADSTS50011 at sign-in) leaves no trace in this cluster.
     */
    @Test
    void listReportsTheRedirectUrisTheOperatorPublished() {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName("acme");
        tenant.getStatus()
                .setRedirectUris(List.of(
                        "https://apus.example.dev/t/acme/auth/callback",
                        "https://apus.example.dev/t/acme/auth/silent-renew"));
        repository.put(tenant);

        var response = controller.list(platformAdmin());

        assertEquals(
                List.of(
                        "https://apus.example.dev/t/acme/auth/callback",
                        "https://apus.example.dev/t/acme/auth/silent-renew"),
                response.body().get(0).redirectUris());
    }

    /** A tenant with no application instance reports an empty list, never null. */
    @Test
    void listReportsNoRedirectUrisForATenantWithoutAnInstance() {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName("acme");
        repository.put(tenant);

        var response = controller.list(platformAdmin());

        assertTrue(response.body().get(0).redirectUris().isEmpty());
    }
}
