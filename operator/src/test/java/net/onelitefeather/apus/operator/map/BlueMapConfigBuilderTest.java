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
package net.onelitefeather.apus.operator.map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import java.util.List;
import java.util.Map;
import net.onelitefeather.apus.operator.api.BlueMapMap;
import org.junit.jupiter.api.Test;

class BlueMapConfigBuilderTest {

    private BlueMapMap map() {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(new ObjectMetaBuilder()
                .withName("survival-overworld")
                .withNamespace("bluemap-friends")
                .build());
        map.getSpec().getSource().setDimension("minecraft:overworld");
        map.getSpec().getStorage().setPrefix("survival");
        return map;
    }

    private BlueMapConfigBuilder.BucketBinding binding() {
        return new BlueMapConfigBuilder.BucketBinding(
                "apus-friends-survival", "http://rook-ceph-rgw.example.svc:80", "us-east-1");
    }

    /** Builds a {@link BlueMapMap} identified solely by its id, for the hosting-config tests. */
    private BlueMapMap map(String id) {
        BlueMapMap map = new BlueMapMap();
        map.setMetadata(
                new ObjectMetaBuilder().withName(id).withNamespace("bluemap-friends").build());
        map.getSpec().getStorage().setPrefix(id);
        return map;
    }

    /** Builds a {@link BlueMapConfigBuilder.BucketBinding} for the given bucket, for the hosting-config tests. */
    private BlueMapConfigBuilder.BucketBinding binding(String bucketName) {
        return new BlueMapConfigBuilder.BucketBinding(
                bucketName, "http://rook-ceph-rgw.example.svc:80", "us-east-1");
    }

    @Test
    void coreConfigEnablesTheResourceDownload() {
        Map<String, String> files = BlueMapConfigBuilder.build(map(), binding());

        // Without accept-download BlueMap refuses to fetch Minecraft resources
        // and every render exits with code 2.
        assertTrue(files.get("core.conf").contains("accept-download: true"), files.get("core.conf"));
    }

    @Test
    void storageConfigUsesTheVerifiedS3Format() {
        Map<String, String> files = BlueMapConfigBuilder.build(map(), binding());
        String s3 = files.get("storages/s3.conf");

        assertTrue(s3.contains("storage-type: \"themeinerlp:s3\""), s3);
        assertTrue(s3.contains("bucket-name: \"apus-friends-survival\""), s3);
        assertTrue(s3.contains("root-path: \"survival\""), s3);
        assertTrue(s3.contains("force-path-style: true"), s3);
    }

    @Test
    void neverPutsCredentialsIntoTheConfigMap() {
        Map<String, String> files = BlueMapConfigBuilder.build(map(), binding());

        // Credentials live in the Rook-managed Secret and are injected as environment
        // variables at pod start. A ConfigMap is world-readable within the namespace.
        for (Map.Entry<String, String> file : files.entrySet()) {
            assertFalse(
                    file.getValue().contains("secret-access-key: \""),
                    "credentials must not be in " + file.getKey());
            assertFalse(
                    file.getValue().contains("access-key-id: \""), "credentials must not be in " + file.getKey());
        }
    }

    @Test
    void mapConfigCarriesTheDimension() {
        Map<String, String> files = BlueMapConfigBuilder.build(map(), binding());

        assertTrue(
                files.get("maps/survival-overworld.conf").contains("minecraft:overworld"),
                files.toString());
    }

    @Test
    void hostingConfigContainsOneMapFilePerMap() {
        Map<String, String> files = BlueMapConfigBuilder.buildForHosting(
                List.of(map("survival-overworld"), map("creative-overworld")),
                List.of(binding("bucket-a"), binding("bucket-b")),
                8100);

        assertTrue(files.containsKey("maps/survival-overworld.conf"), files.keySet().toString());
        assertTrue(files.containsKey("maps/creative-overworld.conf"), files.keySet().toString());
    }

    @Test
    void hostingConfigContainsAWebserverConfigBoundToAllInterfaces() {
        Map<String, String> files =
                BlueMapConfigBuilder.buildForHosting(List.of(map("survival-overworld")), List.of(binding("bucket-a")), 8100);

        String webserver = files.get("webserver.conf");
        assertNotNull(webserver, files.keySet().toString());
        assertTrue(webserver.contains("8100"), webserver);
        // A pod must accept connections from the service, not just from localhost. Verified
        // against BlueMap 5.23's own generated default webserver.conf (run the CLI with an
        // empty config folder -- see BlueMapConfigBuilder's class Javadoc): this version has
        // no bind-address/ip setting at all, the webserver always listens on all interfaces.
        // There is no key to set, so the fact is documented in a comment instead.
        assertTrue(webserver.contains("0.0.0.0"), webserver);
    }

    @Test
    void eachMapGetsItsOwnStorageBecauseBucketsCanDiffer() {
        Map<String, String> files = BlueMapConfigBuilder.buildForHosting(
                List.of(map("a"), map("b")), List.of(binding("bucket-a"), binding("bucket-b")), 8100);

        assertTrue(files.get("maps/a.conf").contains("storage: \"a\""), files.get("maps/a.conf"));
        assertTrue(files.get("maps/b.conf").contains("storage: \"b\""), files.get("maps/b.conf"));
        assertTrue(files.containsKey("storages/a.conf"), files.keySet().toString());
        assertTrue(files.containsKey("storages/b.conf"), files.keySet().toString());
    }

    @Test
    void neverPutsCredentialsIntoTheHostingConfig() {
        Map<String, String> files =
                BlueMapConfigBuilder.buildForHosting(List.of(map("a")), List.of(binding("bucket-a")), 8100);

        for (Map.Entry<String, String> file : files.entrySet()) {
            assertFalse(
                    file.getValue().contains("secret-access-key: \""),
                    "credentials must not be in " + file.getKey());
        }
    }
}
