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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.security.authentication.Authentication;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.api.policy.TenantPolicy;
import net.onelitefeather.apus.api.policy.TenantPolicyReader;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.tenant.InMemoryTenantRepository;
import net.onelitefeather.apus.api.security.TenantResolver;
import net.onelitefeather.apus.api.support.PrincipalResolver;
import net.onelitefeather.apus.operator.api.PolicyEntry;
import net.onelitefeather.apus.operator.api.Tenant;
import org.junit.jupiter.api.Test;

/**
 * Policy enforcement where a tenant meets it: creating a source.
 *
 * <p>The neighbouring {@link WorldSourceControllerTest} covers the endpoint without any policy;
 * this file only adds what a policy changes, so a regression in one is not hidden by the other.
 */
class WorldSourcePolicyTest {

    private static final String TENANT = "acme";

    private final InMemoryWorldSourceRepository repository = new InMemoryWorldSourceRepository();
    private final InMemoryTenantRepository tenants = new InMemoryTenantRepository();
    private final WorldSourceController controller = new WorldSourceController(
            repository,
            new PrincipalResolver(),
            new TenantResolver(),
            new TenantPolicy(),
            new TenantPolicyReader(tenants));

    private static Authentication operator() {
        return Authentication.build(
                "dave", List.of("tenant-operator"), Map.of(PrincipalResolver.TENANT_CLAIM, TENANT));
    }

    private void givenPolicy(String key, String type, String value, boolean locked) {
        Tenant tenant = new Tenant();
        tenant.getMetadata().setName(TENANT);
        PolicyEntry entry = new PolicyEntry();
        entry.setKey(key);
        entry.setType(type);
        entry.setValue(value);
        entry.setLocked(locked);
        tenant.getSpec().setPolicy(List.of(entry));
        tenants.create(tenant);
    }

    private void createSource(String type, String poll, Integer keepVersions) {
        controller.create(
                operator(), new CreateWorldSourceRequest("world-src", type, null, null, poll, null, keepVersions));
    }

    @Test
    void aTenantWithNoPolicyIsUnaffected() {
        // The guarantee that matters most: nothing this feature added changes what a tenant
        // without a policy may do.
        assertDoesNotThrow(() -> createSource("s3", "1s", 99));
    }

    @Test
    void aSourceTypeOutsideALockedListIsRefused() {
        givenPolicy("source.types.allowed", "stringList", "s3", true);

        assertThrows(BadRequestException.class, () -> createSource("pterodactyl", null, null));
    }

    @Test
    void theRefusalNamesTheOptionSoTheTenantCanAskAboutIt() {
        // Without the key in the message a tenant can only report "it says no", and an
        // administrator has to guess which of their own rules did it.
        givenPolicy("source.types.allowed", "stringList", "s3", true);

        BadRequestException thrown =
                assertThrows(BadRequestException.class, () -> createSource("pterodactyl", null, null));

        assertTrue(thrown.getMessage().contains("source.types.allowed"), thrown.getMessage());
    }

    @Test
    void aSourceTypeInsideALockedListIsAccepted() {
        givenPolicy("source.types.allowed", "stringList", "s3,push", true);

        assertDoesNotThrow(() -> createSource("push", null, null));
    }

    @Test
    void anUnlockedListDoesNotRefuse() {
        // The difference between "override" and "lock", asserted where a user would meet it.
        givenPolicy("source.types.allowed", "stringList", "s3", false);

        assertDoesNotThrow(() -> createSource("pterodactyl", null, null));
    }

    @Test
    void aPollShorterThanALockedMinimumIsRefused() {
        givenPolicy("source.poll.minimum", "duration", "5m", true);

        assertThrows(BadRequestException.class, () -> createSource("s3", "30s", null));
        assertDoesNotThrow(() -> createSource("s3", "10m", null));
    }

    @Test
    void keepVersionsAboveALockedMaximumIsRefused() {
        givenPolicy("source.keepVersions.maximum", "integer", "3", true);

        assertThrows(BadRequestException.class, () -> createSource("s3", null, 4));
        assertDoesNotThrow(() -> createSource("s3", null, 3));
    }

    @Test
    void shapeValidationIsReportedBeforePolicy() {
        // Both wrong: an unknown type (shape) that is also outside the allowed list (policy).
        // The caller must hear about the shape, which they can fix themselves, rather than about
        // a rule they would have to ask an administrator to change.
        givenPolicy("source.types.allowed", "stringList", "s3", true);

        BadRequestException thrown = assertThrows(BadRequestException.class, () -> createSource("ftp", null, null));

        assertTrue(thrown.getMessage().contains("type must be one of"), thrown.getMessage());
    }

    @Test
    void anUnknownKeyIsNotEnforcedEvenWhenLocked() {
        givenPolicy("source.poll.maximum", "duration", "5m", true);

        assertDoesNotThrow(() -> createSource("s3", "1h", null));
    }
}
