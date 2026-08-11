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

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.BlueMapRender;
import net.onelitefeather.apus.operator.api.Ref;
import org.junit.jupiter.api.Test;

class BlueMapRenderControllerTest {

    private final InMemoryBlueMapRenderRepository repository = new InMemoryBlueMapRenderRepository();
    private final BlueMapRenderController controller =
            new BlueMapRenderController(repository, new PrincipalResolver(), new TenantResolver());

    private static Authentication viewer(String tenant) {
        return Authentication.build("carol", List.of("tenant-viewer"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication noRoles(String tenant) {
        return Authentication.build("service-token", List.of(), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
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
}
