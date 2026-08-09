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
package net.onelitefeather.apus.api.rest.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.tenant.InMemoryTenantRepository;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import net.onelitefeather.apus.operator.api.Ref;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.Test;

class BlueMapRenderControllerTest {

    private final InMemoryBlueMapRenderRepository repository = new InMemoryBlueMapRenderRepository();
    private final InMemoryTenantRepository tenantRepository = new InMemoryTenantRepository();
    private final BlueMapRenderController controller = new BlueMapRenderController(
            repository, new PrincipalResolver(), new TenantResolver(), tenantRepository);

    private static Authentication viewer(String tenant) {
        return Authentication.build("carol", List.of("tenant-viewer"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication noRoles(String tenant) {
        return Authentication.build("service-token", List.of(), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication platformAdmin() {
        return Authentication.build("root", List.of("platform-admin"), Map.of());
    }

    private static Authentication owner(String tenant) {
        return Authentication.build("alice", List.of("tenant-owner"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication operator(String tenant) {
        return Authentication.build("bob", List.of("tenant-operator"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Tenant tenant(String name, String namespace) {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName(name);
        tenant.getStatus().setNamespace(namespace);
        return tenant;
    }

    private static BlueMapRender render(String name, String mapName) {
        BlueMapRender render = new BlueMapRender();
        render.getMetadata().setName(name);
        Ref mapRef = new Ref();
        mapRef.setName(mapName);
        render.getSpec().setMapRef(mapRef);
        render.getStatus().setPhase("Rendering");
        return render;
    }

    @Test
    void listReturnsOnlyRendersInTheCallersOwnNamespace() {
        repository.put("bluemap-acme", render("render-1", "survival-overworld"));
        repository.put("bluemap-globex", render("foreign-render", "creative-overworld"));

        var response = controller.list(viewer("acme"));

        assertEquals(1, response.body().size());
        assertEquals("render-1", response.body().get(0).name());
    }

    @Test
    void listRejectsACallerWithNoTenantRole() {
        assertThrows(ForbiddenException.class, () -> controller.list(noRoles("acme")));
    }

    @Test
    void getByIdReturnsARenderInTheCallersOwnNamespace() {
        repository.put("bluemap-acme", render("render-1", "survival-overworld"));

        var response = controller.getById(viewer("acme"), "render-1");

        assertEquals("render-1", response.body().name());
        assertEquals("Rendering", response.body().phase());
    }

    @Test
    void getByIdReturns404ForAForeignTenantsRender() {
        repository.put("bluemap-globex", render("render-1", "creative-overworld"));

        assertThrows(NotFoundException.class, () -> controller.getById(viewer("acme"), "render-1"));
    }

    @Test
    void getByIdRejectsACallerWithNoTenantRole() {
        repository.put("bluemap-acme", render("render-1", "survival-overworld"));
        assertThrows(ForbiddenException.class, () -> controller.getById(noRoles("acme"), "render-1"));
    }

    @Test
    void listClusterReturnsRendersAcrossEveryTenantForAPlatformAdmin() {
        tenantRepository.put(tenant("acme", "bluemap-acme"));
        tenantRepository.put(tenant("globex", "bluemap-globex"));
        repository.put("bluemap-acme", render("render-1", "survival-overworld"));
        repository.put("bluemap-globex", render("render-2", "creative-overworld"));

        var response = controller.listCluster(platformAdmin());

        assertEquals(200, response.getStatus().getCode());
        assertEquals(2, response.body().size());
        assertTrue(response.body().stream()
                .anyMatch(entry -> entry.tenant().equals("acme") && entry.render().name().equals("render-1")));
        assertTrue(response.body().stream()
                .anyMatch(entry -> entry.tenant().equals("globex") && entry.render().name().equals("render-2")));
    }

    @Test
    void listClusterSkipsATenantWithNoNamespaceInStatusYet() {
        tenantRepository.put(tenant("brandNew", null));

        var response = controller.listCluster(platformAdmin());

        assertEquals(0, response.body().size());
    }

    /**
     * The security-critical case (task brief C2): every role other than {@code platform-admin}
     * must be rejected, not just "a caller with no roles" -- including the tenant-level roles
     * that *do* pass {@code /api/renders}' own gate, since this is the one endpoint on this
     * controller that would otherwise leak every tenant's renders to any authenticated caller.
     */
    @Test
    void listClusterRejectsEveryNonPlatformAdminRole() {
        assertThrows(ForbiddenException.class, () -> controller.listCluster(owner("acme")));
        assertThrows(ForbiddenException.class, () -> controller.listCluster(operator("acme")));
        assertThrows(ForbiddenException.class, () -> controller.listCluster(viewer("acme")));
        assertThrows(ForbiddenException.class, () -> controller.listCluster(noRoles("acme")));
    }
}
