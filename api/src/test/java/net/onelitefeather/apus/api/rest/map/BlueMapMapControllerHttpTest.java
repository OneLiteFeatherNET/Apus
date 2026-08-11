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

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.token.generator.TokenGenerator;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Proves the three binding invariants from the phase 5a consolidation brief over a real,
 * embedded HTTP server -- not by calling {@link BlueMapMapController}'s methods directly, the
 * way every other controller test in this module does (see those classes' Javadoc: neither
 * {@code micronaut-test-junit5} nor an HTTP client was a test dependency before this
 * consolidation). This is what actually proves the {@code @Secured} annotations and the
 * exception handlers under {@code rest/support} are wired into the real filter chain, not just
 * that the plain Java methods behave correctly in isolation:
 *
 * <ul>
 *   <li>{@link #requestWithoutATokenIsUnauthorized()} -- no {@code Authorization} header at all.
 *   <li>{@link #requestWithAValidTokenButNoTenantRoleIsForbidden()} -- a validly signed token
 *       whose caller holds none of the three tenant roles.
 *   <li>{@link #resourceInAForeignTenantsNamespaceIs404NotForbidden()} -- a validly signed,
 *       sufficiently privileged token for tenant {@code acme}, for a map that exists only in
 *       tenant {@code globex}'s namespace -- the central design-spec §10.3 invariant: the API
 *       must not distinguish "forbidden" from "does not exist" for a foreign tenant's resource.
 * </ul>
 *
 * <p>Runs under the {@code apitest} Micronaut environment, which replaces {@link
 * FabricBlueMapMapRepository} with {@link TestBlueMapMapRepository} (see its Javadoc) so these
 * tests need neither Docker nor a reachable Kubernetes API server -- that real-cluster proof is
 * {@code TenantIsolationIntegrationTest}'s job. JWT signing/validation is configured in {@code
 * src/test/resources/application-test.yml} with a symmetric test-only secret so tokens can be
 * minted here without a real identity broker.
 */
@MicronautTest(environments = "apitest")
class BlueMapMapControllerHttpTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TestBlueMapMapRepository mapRepository;

    @Inject
    TokenGenerator tokenGenerator;

    @BeforeEach
    void clearFixtures() {
        mapRepository.clear();
    }

    @Test
    void requestWithoutATokenIsUnauthorized() {
        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking().exchange(HttpRequest.GET("/api/maps")));

        assertEquals(HttpStatus.UNAUTHORIZED, e.getStatus());
    }

    @Test
    void requestWithAValidTokenButNoTenantRoleIsForbidden() {
        // A real, validly signed token -- authentication succeeds -- for a caller with zero
        // recognised tenant roles (e.g. a narrowly scoped service token, design spec §10.3).
        String token = token("service-token", List.of(), "acme");

        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(HttpRequest.GET("/api/maps").bearerAuth(token)));

        assertEquals(HttpStatus.FORBIDDEN, e.getStatus());
    }

    @Test
    void resourceInAForeignTenantsNamespaceIs404NotForbidden() {
        BlueMapMap foreignMap = new BlueMapMap();
        foreignMap.getMetadata().setName("globex-only-map");
        mapRepository.put("bluemap-globex", foreignMap);

        // Sufficiently privileged (tenant-viewer), but for the wrong tenant: "acme", not
        // "globex". The map exists -- just not where this caller may look.
        String token = token("carol", List.of("tenant-viewer"), "acme");

        HttpClientResponseException e = assertThrows(
                HttpClientResponseException.class,
                () -> client.toBlocking()
                        .exchange(HttpRequest.GET("/api/maps/globex-only-map").bearerAuth(token)));

        assertEquals(
                HttpStatus.NOT_FOUND,
                e.getStatus(),
                "a foreign tenant's resource must be a plain 404, not a 403 that would confirm it exists");
    }

    @Test
    void resourceInTheCallersOwnNamespaceIsFound() {
        // Sanity check alongside the two failure cases above: the same mechanism that blocks a
        // foreign tenant must not also block the caller's own tenant.
        BlueMapMap ownMap = new BlueMapMap();
        ownMap.getMetadata().setName("survival-overworld");
        mapRepository.put("bluemap-acme", ownMap);

        String token = token("carol", List.of("tenant-viewer"), "acme");

        var response = client.toBlocking()
                .exchange(HttpRequest.GET("/api/maps/survival-overworld").bearerAuth(token), BlueMapMapResponse.class);

        assertEquals(HttpStatus.OK, response.getStatus());
        assertEquals("survival-overworld", response.body().name());
    }

    private String token(String subject, List<String> roles, String tenant) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", subject);
        claims.put("roles", roles);
        claims.put(PrincipalResolver.TENANT_CLAIM, tenant);
        claims.put("iss", "https://apus-test-issuer.internal");
        return tokenGenerator
                .generateToken(claims)
                .orElseThrow(() -> new IllegalStateException("test token generation failed"));
    }
}
