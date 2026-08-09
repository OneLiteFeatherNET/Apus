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
package net.onelitefeather.apus.api.rest.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.api.rest.support.PrincipalResolver;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import org.junit.jupiter.api.Test;

/**
 * Covers the two id-based endpoints ({@code getById}, {@code triggerRender}) with the full
 * three-case shape task-2-brief.md asks for: happy path, foreign tenant -> 404, insufficient
 * role -> 403. {@code list} gets happy path plus a cross-tenant isolation check instead of a 404
 * case -- a collection has no single id that could belong to a foreign tenant, so "404" does not
 * apply to it the way it does to a by-id lookup; isolation is the equivalent invariant for a
 * list (see {@code WorldSourceControllerTest} for the same reasoning applied there).
 */
class BlueMapMapControllerTest {

    private final InMemoryBlueMapMapRepository mapRepository = new InMemoryBlueMapMapRepository();
    private final InMemoryBlueMapRenderRepository renderRepository = new InMemoryBlueMapRenderRepository();
    private final BlueMapMapController controller = new BlueMapMapController(
            mapRepository, renderRepository, new PrincipalResolver(), new TenantResolver());

    private static Authentication viewer(String tenant) {
        return Authentication.build("carol", List.of("tenant-viewer"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication operator(String tenant) {
        return Authentication.build(
                "dave", List.of("tenant-operator"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication noRoles(String tenant) {
        return Authentication.build("service-token", List.of(), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static BlueMapMap map(String name) {
        BlueMapMap map = new BlueMapMap();
        map.getMetadata().setName(name);
        return map;
    }

    @Test
    void listReturnsOnlyMapsInTheCallersOwnNamespace() {
        mapRepository.put("bluemap-acme", map("survival-overworld"));
        mapRepository.put("bluemap-globex", map("foreign-map"));

        var response = controller.list(viewer("acme"));

        assertEquals(1, response.body().size());
        assertEquals("survival-overworld", response.body().get(0).name());
    }

    @Test
    void listRejectsACallerWithNoTenantRole() {
        assertThrows(ForbiddenException.class, () -> controller.list(noRoles("acme")));
    }

    @Test
    void getByIdReturnsAMapInTheCallersOwnNamespace() {
        mapRepository.put("bluemap-acme", map("survival-overworld"));

        var response = controller.getById(viewer("acme"), "survival-overworld");

        assertEquals("survival-overworld", response.body().name());
    }

    @Test
    void getByIdReturns404ForAForeignTenantsMap() {
        // The central rule under test: a map that exists, but only in a different tenant's
        // namespace, must be indistinguishable from one that does not exist at all.
        mapRepository.put("bluemap-globex", map("survival-overworld"));

        assertThrows(NotFoundException.class, () -> controller.getById(viewer("acme"), "survival-overworld"));
    }

    @Test
    void getByIdRejectsACallerWithNoTenantRole() {
        mapRepository.put("bluemap-acme", map("survival-overworld"));
        assertThrows(ForbiddenException.class, () -> controller.getById(noRoles("acme"), "survival-overworld"));
    }

    @Test
    void triggerRenderCreatesABlueMapRenderReferencingTheMap() {
        mapRepository.put("bluemap-acme", map("survival-overworld"));

        var response = controller.triggerRender(operator("acme"), "survival-overworld", null);

        assertEquals(201, response.getStatus().getCode());
        assertEquals("survival-overworld", response.body().mapRef());
        assertTrue(renderRepository.list("bluemap-acme").stream()
                .anyMatch(r -> "survival-overworld".equals(r.getSpec().getMapRef().getName())));
    }

    @Test
    void triggerRenderHonoursTheForceFlag() {
        mapRepository.put("bluemap-acme", map("survival-overworld"));

        var response = controller.triggerRender(operator("acme"), "survival-overworld", new TriggerRenderRequest(true));

        assertTrue(response.body().force());
    }

    @Test
    void triggerRenderReturns404ForAForeignTenantsMapWithoutCreatingARender() {
        mapRepository.put("bluemap-globex", map("survival-overworld"));

        assertThrows(
                NotFoundException.class, () -> controller.triggerRender(operator("acme"), "survival-overworld", null));
        assertEquals(0, renderRepository.list("bluemap-acme").size());
    }

    @Test
    void triggerRenderRejectsAViewer() {
        mapRepository.put("bluemap-acme", map("survival-overworld"));
        assertThrows(
                ForbiddenException.class, () -> controller.triggerRender(viewer("acme"), "survival-overworld", null));
    }
}
