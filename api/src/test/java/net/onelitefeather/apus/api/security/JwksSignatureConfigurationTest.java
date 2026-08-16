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
package net.onelitefeather.apus.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.micronaut.security.token.jwt.signature.jwks.JwksSignatureConfiguration;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import java.util.Collection;
import org.junit.jupiter.api.Test;

/**
 * Guards the one line of {@code src/main/resources/application.yml} that, when wrong, disables
 * authentication completely while leaving the application looking perfectly healthy.
 *
 * <p><b>The bug this exists for.</b> That file configured the JWKS endpoint as {@code jwks-uri}.
 * {@code JwksSignatureConfigurationProperties} has {@code setUrl(String)} and no
 * {@code setJwksUri}, so the key bound to nothing — silently. The configuration bean was still
 * created, {@code ReactiveJwksSignature} was still wired to it, and the fetcher was simply handed
 * a {@code null} URL. No HTTP request was ever made, no exception was thrown, nothing above DEBUG
 * was logged, and the JWK Set stayed empty for the life of the process. Every authenticated
 * request was answered {@code 401} no matter which broker issued the token; the entire evidence
 * at DEBUG was one line reading "JWK Set Key IDs:" with nothing after it.
 *
 * <p><b>Why no existing test caught it.</b> {@code src/test/resources/application-test.yml}
 * supplies the {@code APUS_JWT_JWKS_URI} placeholder and a symmetric HS256 secret, and every
 * token the other tests mint is signed with that secret. Signature validation therefore succeeds
 * through the secret verifier and the JWKS verifier is never reached — so the tests exercised the
 * placeholder but never the mapping from that placeholder onto a property Micronaut binds. This
 * test closes exactly that gap: it asserts the value arrives on the bean that will be asked for
 * it, which is the part production depends on and the tests did not.
 *
 * <p>Deliberately reaches no network. The URL under test is the unreachable placeholder from
 * {@code application-test.yml}; dereferencing it is not the point, binding it is.
 */
@MicronautTest(environments = "apitest")
class JwksSignatureConfigurationTest {

    /** Exactly what src/test/resources/application-test.yml puts in APUS_JWT_JWKS_URI. */
    private static final String EXPECTED_URL = "http://127.0.0.1:1/unused-jwks-endpoint";

    @Inject
    Collection<JwksSignatureConfiguration> jwksConfigurations;

    @Test
    void applicationYmlBindsTheJwksEndpointOntoAPropertyMicronautUnderstands() {
        assertFalse(jwksConfigurations.isEmpty(),
            "no JwksSignatureConfiguration exists -- nothing would verify a broker-signed token");

        JwksSignatureConfiguration jwks = jwksConfigurations.stream()
            .filter(configuration -> "apus-issuer".equals(configuration.getName()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no JWKS configuration named apus-issuer -- application.yml's provider name changed"));

        // The assertion that matters, and the one whose absence let a 401-for-everything ship:
        // a present bean with an absent URL fetches nothing, logs nothing, and fails every token.
        assertNotNull(jwks.getUrl(),
            "the JWKS URL did not bind. Check the property name in application.yml against "
                + "JwksSignatureConfigurationProperties -- it is `url`, not `jwks-uri`, and the "
                + "wrong one fails silently.");
        assertEquals(EXPECTED_URL, jwks.getUrl(),
            "the JWKS URL bound, but not from APUS_JWT_JWKS_URI -- the placeholder chain broke");
    }
}
