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
package net.onelitefeather.apus.api.rest.push;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.onelitefeather.apus.api.rest.ingest.InMemoryWorldIngestRepository;
import net.onelitefeather.apus.api.rest.support.BadRequestException;
import net.onelitefeather.apus.api.rest.support.NotFoundException;
import net.onelitefeather.apus.operator.api.WorldSource;
import org.junit.jupiter.api.Test;

/**
 * The abuse cases matter more than the good path here (task brief) -- this is the one endpoint in
 * the whole module that authenticates via a bare secret in the URL rather than a JWT, so most of
 * these tests are deliberately about what happens with a wrong, foreign, or absent token, not
 * just the happy path.
 */
class PushControllerTest {

    private final InMemoryPushTokenRepository tokenRepository = new InMemoryPushTokenRepository();
    private final InMemoryWorldSourceRepository sourceRepository = new InMemoryWorldSourceRepository();
    private final InMemoryWorldIngestRepository ingestRepository = new InMemoryWorldIngestRepository();
    private final PushController controller = new PushController(tokenRepository, sourceRepository, ingestRepository);

    private static WorldSource pushSource(String name, String... worldNames) {
        WorldSource source = new WorldSource();
        source.getMetadata().setName(name);
        source.getSpec().setType("push");
        for (String worldName : worldNames) {
            var selector = new WorldSource.WorldSelector();
            selector.setName(worldName);
            source.getSpec().getWorlds().add(selector);
        }
        return source;
    }

    @Test
    void validTokenAndSourceCreatesOneWorldIngestPerConfiguredWorld() {
        tokenRepository.put("acme-secret-token", "bluemap-acme");
        sourceRepository.put("bluemap-acme", pushSource("survival", "world", "world_nether"));

        var response = controller.report("acme-secret-token", new PushReportRequest("survival", "backup-42"));

        assertEquals(201, response.getStatus().getCode());
        assertEquals(2, response.body().worldIngests().size());
        assertEquals(2, ingestRepository.forNamespace("bluemap-acme").size());
        var ingest = ingestRepository.forNamespace("bluemap-acme").get(0);
        assertEquals("survival", ingest.getSpec().getSourceRef().getName());
        assertEquals("backup-42", ingest.getSpec().getSourceVersion());
    }

    @Test
    void unknownTokenIsNotFound() {
        sourceRepository.put("bluemap-acme", pushSource("survival", "world"));

        assertThrows(
                NotFoundException.class,
                () -> controller.report("this-token-does-not-exist", new PushReportRequest("survival", "v1")));
    }

    @Test
    void blankTokenIsNotFound() {
        assertThrows(NotFoundException.class, () -> controller.report("", new PushReportRequest("survival", "v1")));
    }

    @Test
    void tokenForOneTenantCannotReachAnotherTenantsSourceByName() {
        // "carol-secret" only authorizes bluemap-acme -- globex-survival exists, but under a
        // different namespace this token was never issued for.
        tokenRepository.put("carol-secret", "bluemap-acme");
        sourceRepository.put("bluemap-globex", pushSource("globex-survival", "world"));

        assertThrows(
                NotFoundException.class,
                () -> controller.report("carol-secret", new PushReportRequest("globex-survival", "v1")));
    }

    @Test
    void aTokenValidForOneNamespaceNeverCreatesAnIngestInAnotherNamespace() {
        tokenRepository.put("acme-secret-token", "bluemap-acme");
        tokenRepository.put("globex-secret-token", "bluemap-globex");
        sourceRepository.put("bluemap-acme", pushSource("survival", "world"));
        sourceRepository.put("bluemap-globex", pushSource("survival", "world"));

        controller.report("acme-secret-token", new PushReportRequest("survival", "v1"));

        assertEquals(1, ingestRepository.forNamespace("bluemap-acme").size());
        assertTrue(ingestRepository.forNamespace("bluemap-globex").isEmpty());
    }

    @Test
    void unknownSourceNameForAValidTokenIsNotFound() {
        tokenRepository.put("acme-secret-token", "bluemap-acme");

        assertThrows(
                NotFoundException.class,
                () -> controller.report("acme-secret-token", new PushReportRequest("no-such-source", "v1")));
    }

    @Test
    void aSourceThatIsNotOfTypePushIsNotFoundEvenWithAValidToken() {
        tokenRepository.put("acme-secret-token", "bluemap-acme");
        WorldSource s3Source = new WorldSource();
        s3Source.getMetadata().setName("survival");
        s3Source.getSpec().setType("s3");
        sourceRepository.put("bluemap-acme", s3Source);

        assertThrows(
                NotFoundException.class,
                () -> controller.report("acme-secret-token", new PushReportRequest("survival", "v1")));
    }

    @Test
    void aSourceWithNoConfiguredWorldsIsRejected() {
        tokenRepository.put("acme-secret-token", "bluemap-acme");
        sourceRepository.put("bluemap-acme", pushSource("survival"));

        assertThrows(
                BadRequestException.class,
                () -> controller.report("acme-secret-token", new PushReportRequest("survival", "v1")));
    }

    @Test
    void missingSourceNameOrVersionIsRejected() {
        tokenRepository.put("acme-secret-token", "bluemap-acme");
        sourceRepository.put("bluemap-acme", pushSource("survival", "world"));

        assertThrows(
                BadRequestException.class, () -> controller.report("acme-secret-token", new PushReportRequest(null, "v1")));
        assertThrows(
                BadRequestException.class,
                () -> controller.report("acme-secret-token", new PushReportRequest("survival", null)));
        assertThrows(BadRequestException.class, () -> controller.report("acme-secret-token", null));
    }
}
