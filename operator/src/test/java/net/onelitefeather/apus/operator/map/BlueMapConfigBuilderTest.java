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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
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
}
