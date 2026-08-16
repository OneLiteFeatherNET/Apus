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
package net.onelitefeather.apus.api.rest.worldsource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.policy.TenantPolicy;
import net.onelitefeather.apus.api.policy.TenantPolicyReader;
import net.onelitefeather.apus.api.rest.tenant.InMemoryTenantRepository;
import net.onelitefeather.apus.api.security.ForbiddenException;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.WorldSource;
import org.junit.jupiter.api.Test;

class WorldSourceControllerTest {

    private final InMemoryWorldSourceRepository repository = new InMemoryWorldSourceRepository();
    private final InMemoryTenantRepository tenants = new InMemoryTenantRepository();
    private final WorldSourceController controller = new WorldSourceController(
            repository,
            new PrincipalResolver(),
            new TenantResolver(),
            new TenantPolicy(),
            new TenantPolicyReader(tenants));

    private static Authentication viewer(String tenant) {
        return Authentication.build("carol", List.of("tenant-viewer"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication operator(String tenant) {
        return Authentication.build(
                "dave", List.of("tenant-operator"), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static Authentication noRoles(String tenant) {
        // §10.3 service-token shape: tenant claim present, no recognised role.
        return Authentication.build("service-token", List.of(), Map.of(PrincipalResolver.TENANT_CLAIM, tenant));
    }

    private static WorldSource source(String name) {
        WorldSource source = new WorldSource();
        source.getMetadata().setName(name);
        source.getSpec().setType("s3");
        return source;
    }

    @Test
    void listReturnsOnlySourcesInTheCallersOwnNamespace() {
        repository.put("bluemap-acme", source("survival"));
        repository.put("bluemap-globex", source("foreign-source"));

        var response = controller.list(viewer("acme"));

        assertEquals(200, response.getStatus().getCode());
        assertEquals(1, response.body().size());
        assertEquals("survival", response.body().get(0).name());
    }

    @Test
    void listRejectsACallerWithNoTenantRole() {
        assertThrows(ForbiddenException.class, () -> controller.list(noRoles("acme")));
    }

    @Test
    void createAddsASourceInTheCallersOwnNamespace() {
        var request = new CreateWorldSourceRequest(
                "survival",
                "s3",
                new CreateWorldSourceRequest.S3Request("https://s3.example.net", "bucket", "prefix", "s3-creds"),
                null,
                null,
                List.of(new CreateWorldSourceRequest.WorldSelectorRequest("world", "auto", "1.21.10")),
                null);

        var response = controller.create(operator("acme"), request);

        assertEquals(201, response.getStatus().getCode());
        assertEquals("survival", response.body().name());
        assertTrue(repository.find("bluemap-acme", "survival").isPresent());
        // The response never carries the Secret name the request supplied.
        assertFalse(response.body().toString().contains("s3-creds"));
    }

    @Test
    void createRejectsAViewer() {
        var request = new CreateWorldSourceRequest("survival", "s3", null, null, null, List.of(), null);
        assertThrows(ForbiddenException.class, () -> controller.create(viewer("acme"), request));
    }
}
