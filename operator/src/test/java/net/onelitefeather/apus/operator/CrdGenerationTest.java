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
package net.onelitefeather.apus.operator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CrdGenerationTest {

    private static Path crdDir() {
        return Path.of(System.getProperty("apus.crd.dir", "build/crds"));
    }

    private static String readAllCrds() throws IOException {
        try (Stream<Path> files = Files.list(crdDir())) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yml")
                            || p.toString().endsWith(".yaml"))
                    .toList();
            StringBuilder all = new StringBuilder();
            for (Path p : yamls) {
                all.append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
            }
            return all.toString();
        }
    }

    @Test
    void generatesACrdForTheTenantResource() throws IOException {
        assertTrue(Files.isDirectory(crdDir()), "CRD output directory must exist: " + crdDir());

        String all = readAllCrds();

        assertTrue(all.contains("bluemap.onelitefeather.net"), "API group missing:\n" + all);
        assertTrue(all.contains("kind: Tenant"), "Tenant kind missing:\n" + all);
        assertTrue(all.contains("plural: tenants"), "plural missing:\n" + all);
    }

    @Test
    void tenantIsClusterScoped() throws IOException {
        String all = readAllCrds();

        // Tenant grants a namespace and a storage quota -- it must never be
        // creatable from inside a tenant namespace.
        assertTrue(all.contains("scope: Cluster"), "Tenant must be cluster-scoped:\n" + all);
    }

    @Test
    void statusSubresourceIsEnabled() throws IOException {
        String all = readAllCrds();

        // Without the status subresource the operator could not update status
        // independently of spec, and every status write would bump the resource version.
        assertTrue(all.contains("status: {}") || all.contains("subresources"),
                "status subresource missing:\n" + all);
    }
}
