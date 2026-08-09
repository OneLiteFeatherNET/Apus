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
package net.onelitefeather.apus.operator.testsupport;

import io.fabric8.kubernetes.client.KubernetesClient;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Assertions;

/**
 * Shared helpers for tests that apply Apus's generated CRD manifests to a real Kubernetes API
 * server (started via Testcontainers) and wait for the API server to register them.
 *
 * <p>Factored out of {@code OperatorIntegrationTest} so a second real-cluster test class (see
 * {@code net.onelitefeather.apus.operator.hosting.BlueMapHostingIntegrationTest}) reuses the
 * exact same apply/await logic rather than re-implementing it -- both classes read CRD YAML from
 * the same {@code apus.crd.dir} system property the {@code operator} module's Gradle build wires
 * up (see {@code operator/build.gradle.kts}).
 */
public final class K3sCrdSupport {

    private K3sCrdSupport() {}

    /**
     * Applies every generated CRD manifest under {@code apus.crd.dir} (default {@code
     * build/crds}) to {@code client} via server-side apply.
     */
    public static void applyGeneratedCrds(KubernetesClient client) {
        Path crdDir = Path.of(System.getProperty("apus.crd.dir", "build/crds"));
        try (var files = Files.list(crdDir)) {
            files.filter(path -> path.toString().endsWith(".yml") || path.toString().endsWith(".yaml"))
                    .forEach(path -> {
                        try (InputStream in = Files.newInputStream(path)) {
                            client.load(in).serverSideApply();
                        } catch (IOException e) {
                            throw new UncheckedIOException("failed to apply CRD manifest " + path, e);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to list CRD manifests in " + crdDir, e);
        }
    }

    /** Polls until {@code crdName} shows up as a registered CustomResourceDefinition, or fails. */
    public static void awaitCrdRegistration(KubernetesClient client, String crdName, Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        boolean known = false;
        while (System.currentTimeMillis() < deadline && !known) {
            known = client.apiextensions().v1().customResourceDefinitions().list().getItems().stream()
                    .anyMatch(crd -> crdName.equals(crd.getMetadata().getName()));
            if (!known) {
                Thread.sleep(1000);
            }
        }
        Assertions.assertTrue(known, crdName + " CRD must be registered on the API server");
    }
}
