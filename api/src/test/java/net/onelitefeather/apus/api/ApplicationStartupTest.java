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
package net.onelitefeather.apus.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import io.micronaut.runtime.server.EmbeddedServer;
import net.onelitefeather.apus.api.directory.TenantGroupIndexLoader;
import net.onelitefeather.apus.api.security.ImpersonationPolicy;
import org.junit.jupiter.api.Test;

/**
 * Boots a real context with an embedded server and asserts it comes up, then resolves the beans
 * this module's newest wiring added. Deliberately shallow: it asserts nothing about behaviour. Its
 * job is to fail when the application would not start at all -- an unsatisfiable bean, a factory
 * that throws, a configuration property that will not bind.
 *
 * <p><b>What it cannot do, stated because it was written believing otherwise.</b> This test was
 * added in response to an outage: {@code TenantGroupIndexLoader} declared
 * {@code @Scheduled(fixedDelay = "60s")}, a spelling Micronaut's converter rejects, the context
 * failed during {@code start()}, and the api pod went into CrashLoopBackOff and took the whole API
 * down. This test was then checked against that exact bug, reintroduced faithfully -- and it
 * <em>passed</em>. On the plain test classpath the annotation converts fine; it only fails inside
 * the shadowed jar. No {@code @MicronautTest}, not even one that starts a server, can see that
 * class of failure.
 *
 * <p>What does see it is {@code :api:startupSmokeTest}, which boots the shadowed jar itself and is
 * wired into {@code check}. That task was verified in both directions -- green on the fix, red on
 * the reintroduced bug. If you are looking for the guard against "the artifact does not start",
 * it is there, not here.
 */
class ApplicationStartupTest {

    @Test
    void theApplicationStarts() {
        try (ApplicationContext context = ApplicationContext.run(EmbeddedServer.class, "apitest")
                .getApplicationContext()) {
            assertTrue(context.isRunning(), "the application context must be running");

            // Resolved explicitly rather than trusting that startup implies they exist: a bean
            // nothing asks for can be broken in ways a context start never notices.
            assertNotNull(
                    context.getBean(TenantGroupIndexLoader.class),
                    "the tenant group index loader must be resolvable -- it is what maps a token's"
                            + " groups claim onto a tenant");
            assertNotNull(context.getBean(ImpersonationPolicy.class));
        }
    }
}
