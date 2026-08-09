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
package net.onelitefeather.apus.api.rest.hosting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.rest.support.PrincipalResolver;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.operator.api.BlueMapHosting;
import org.junit.jupiter.api.Test;

class BlueMapHostingControllerTest {

    private final InMemoryBlueMapHostingRepository repository = new InMemoryBlueMapHostingRepository();
    private final BlueMapHostingController controller =
            new BlueMapHostingController(repository, new PrincipalResolver(), new TenantResolver());

    private static Authentication viewer(String tenant) {
        return Authentication.build("carol", List.of("tenant-viewer"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication noRoles(String tenant) {
        return Authentication.build("service-token", List.of(), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static BlueMapHosting hosting(String name, String hostname) {
        BlueMapHosting hosting = new BlueMapHosting();
        hosting.getMetadata().setName(name);
        hosting.getSpec().setHostname(hostname);
        return hosting;
    }

    @Test
    void listReturnsOnlyHostingsInTheCallersOwnNamespace() {
        repository.put("bluemap-acme", hosting("survival-hosting", "map.acme.example.net"));
        repository.put("bluemap-globex", hosting("foreign-hosting", "map.globex.example.net"));

        var response = controller.list(viewer("acme"));

        assertEquals(1, response.body().size());
        assertEquals("survival-hosting", response.body().get(0).name());
        assertEquals("map.acme.example.net", response.body().get(0).hostname());
    }

    @Test
    void listRejectsACallerWithNoTenantRole() {
        assertThrows(ForbiddenException.class, () -> controller.list(noRoles("acme")));
    }
}
