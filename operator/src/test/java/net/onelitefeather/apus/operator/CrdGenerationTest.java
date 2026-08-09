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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinition;
import io.fabric8.kubernetes.api.model.apiextensions.v1.CustomResourceDefinitionVersion;
import io.fabric8.kubernetes.client.utils.Serialization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CrdGenerationTest {

    private static Path crdDir() {
        return Path.of(System.getProperty("apus.crd.dir", "build/crds"));
    }

    /**
     * Loads and parses a single generated CRD manifest by its deterministic file name (the
     * fabric8 CRDGenerator names files {@code <plural>.<group>-<version>.yml}). A missing file
     * fails with a message naming exactly which manifest is missing, rather than silently
     * degrading to "the concatenation of everything else happened to contain the right
     * string" -- which stops meaning anything once more than one CRD is generated.
     *
     * <p>Package-private so later tasks adding further CRDs to this module (namespace,
     * storage-user, render-job, ... -- see the phase 2a plan) can reuse it instead of
     * re-implementing file lookup and YAML parsing.
     */
    static CustomResourceDefinition loadCrd(String fileName) {
        Path file = crdDir().resolve(fileName);
        assertTrue(
                Files.isRegularFile(file),
                "expected generated CRD file: " + file
                        + " (does the generator's <plural>.<group>-<version> naming still match?)");
        try (var in = Files.newInputStream(file)) {
            return Serialization.unmarshal(in, CustomResourceDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read/parse " + file, e);
        }
    }

    private static String readAllCrds() throws IOException {
        try (Stream<Path> files = Files.list(crdDir())) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yml")
                            || p.toString().endsWith(".yaml"))
                    .toList();
            StringBuilder all = new StringBuilder();
            for (Path p : yamls) {
                all.append(Files.readString(p)).append('\n');
            }
            return all.toString();
        }
    }

    @Test
    void generatesTheTenantCrdWithExpectedIdentity() {
        CustomResourceDefinition crd = loadCrd("tenants.bluemap.onelitefeather.net-v1.yml");

        assertEquals("bluemap.onelitefeather.net", crd.getSpec().getGroup());
        assertEquals("Tenant", crd.getSpec().getNames().getKind());
        assertEquals("tenants", crd.getSpec().getNames().getPlural());
    }

    @Test
    void tenantIsClusterScoped() {
        CustomResourceDefinition crd = loadCrd("tenants.bluemap.onelitefeather.net-v1.yml");

        // Tenant grants a namespace and a storage quota -- it must never be
        // creatable from inside a tenant namespace. Checked on the Tenant CRD specifically:
        // Phase 2a adds five more (namespaced) CRDs to this module, and a check that merely
        // scans every generated file for the substring "scope: Cluster" would keep passing
        // for as long as *any* of them is cluster-scoped, even if Tenant itself regressed.
        assertEquals("Cluster", crd.getSpec().getScope(), "Tenant must be cluster-scoped");
    }

    @Test
    void tenantStatusSubresourceIsEnabled() {
        CustomResourceDefinition crd = loadCrd("tenants.bluemap.onelitefeather.net-v1.yml");

        Optional<CustomResourceDefinitionVersion> v1alpha1 = crd.getSpec().getVersions().stream()
                .filter(version -> "v1alpha1".equals(version.getName()))
                .findFirst();
        assertTrue(v1alpha1.isPresent(), "expected a v1alpha1 version entry in the Tenant CRD");

        // Without the status subresource the operator could not update status independently
        // of spec, and every status write would bump the resource version.
        assertNotNull(
                v1alpha1.get().getSubresources(), "Tenant v1alpha1 is missing the subresources block");
        assertNotNull(
                v1alpha1.get().getSubresources().getStatus(),
                "Tenant v1alpha1 is missing the status subresource");
    }

    @Test
    void generatesNoForeignCrds() throws IOException {
        // Unlike the assertions above, "does this string appear anywhere across every
        // generated manifest" is exactly the right question here: no file, no matter its
        // name, may define a CRD in a group this operator does not own.
        String all = readAllCrds();

        assertFalse(all.contains("objectbucket.io"), "unexpected objectbucket.io CRD found:\n" + all);
        assertFalse(all.contains("ceph.rook.io"), "unexpected ceph.rook.io CRD found:\n" + all);
    }

    @Test
    void doesNotGenerateCrdsForForeignResources() throws IOException {
        String all = readAllCrds();

        // Rook owns these CRDs; shipping our own copy would fight with Rook's.
        assertTrue(!all.contains("objectbucket.io"), "must not generate Rook CRDs:\n" + all);
        assertTrue(!all.contains("ceph.rook.io"), "must not generate Rook CRDs:\n" + all);
    }

    @Test
    void generatesTheBlueMapMapCrdWithExpectedIdentity() {
        CustomResourceDefinition crd = loadCrd("bluemapmaps.bluemap.onelitefeather.net-v1.yml");

        assertEquals("bluemap.onelitefeather.net", crd.getSpec().getGroup());
        assertEquals("BlueMapMap", crd.getSpec().getNames().getKind());
        assertEquals("bluemapmaps", crd.getSpec().getNames().getPlural());
    }

    @Test
    void blueMapMapIsNamespaceScoped() {
        CustomResourceDefinition crd = loadCrd("bluemapmaps.bluemap.onelitefeather.net-v1.yml");

        // Unlike Tenant, a map belongs to exactly one tenant namespace and must never
        // be creatable across tenant boundaries.
        assertEquals("Namespaced", crd.getSpec().getScope(), "BlueMapMap must be namespace-scoped");
    }

    @Test
    void generatesTheBlueMapRenderCrdWithExpectedIdentity() {
        CustomResourceDefinition crd = loadCrd("bluemaprenders.bluemap.onelitefeather.net-v1.yml");

        assertEquals("bluemap.onelitefeather.net", crd.getSpec().getGroup());
        assertEquals("BlueMapRender", crd.getSpec().getNames().getKind());
        assertEquals("bluemaprenders", crd.getSpec().getNames().getPlural());
    }

    @Test
    void blueMapRenderIsNamespaceScoped() {
        CustomResourceDefinition crd = loadCrd("bluemaprenders.bluemap.onelitefeather.net-v1.yml");

        assertEquals("Namespaced", crd.getSpec().getScope(), "BlueMapRender must be namespace-scoped");
    }

    @Test
    void generatesTheWorldSourceCrdWithExpectedIdentity() {
        CustomResourceDefinition crd = loadCrd("worldsources.bluemap.onelitefeather.net-v1.yml");

        assertEquals("bluemap.onelitefeather.net", crd.getSpec().getGroup());
        assertEquals("WorldSource", crd.getSpec().getNames().getKind());
        assertEquals("worldsources", crd.getSpec().getNames().getPlural());
    }

    @Test
    void worldSourceIsNamespaceScoped() {
        CustomResourceDefinition crd = loadCrd("worldsources.bluemap.onelitefeather.net-v1.yml");

        // A source belongs to exactly one tenant's namespace, exactly like BlueMapMap.
        assertEquals("Namespaced", crd.getSpec().getScope(), "WorldSource must be namespace-scoped");
    }

    @Test
    void generatesTheWorldIngestCrdWithExpectedIdentity() {
        CustomResourceDefinition crd = loadCrd("worldingests.bluemap.onelitefeather.net-v1.yml");

        assertEquals("bluemap.onelitefeather.net", crd.getSpec().getGroup());
        assertEquals("WorldIngest", crd.getSpec().getNames().getKind());
        assertEquals("worldingests", crd.getSpec().getNames().getPlural());
    }

    @Test
    void worldIngestIsNamespaceScoped() {
        CustomResourceDefinition crd = loadCrd("worldingests.bluemap.onelitefeather.net-v1.yml");

        assertEquals("Namespaced", crd.getSpec().getScope(), "WorldIngest must be namespace-scoped");
    }
}
