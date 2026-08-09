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
package net.onelitefeather.apus.telemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class AddonManifestTest {

    private String manifest() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/bluemap.addon.json")) {
            assertNotNull(in, "bluemap.addon.json must be on the classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void declaresTheApusTelemetryId() throws Exception {
        assertTrue(manifest().contains("\"id\""), manifest());
        assertTrue(manifest().contains("apus-telemetry"), manifest());
    }

    @Test
    void entrypointClassExistsAndIsRunnable() throws Exception {
        Matcher matcher = Pattern.compile("\"entrypoint\"\\s*:\\s*\"([^\"]+)\"").matcher(manifest());
        assertTrue(matcher.find(), "manifest must declare an entrypoint");

        String className = matcher.group(1);
        Class<?> entrypoint = assertDoesNotThrow(
                () -> Class.forName(className), "entrypoint class named in bluemap.addon.json must exist");

        assertTrue(Runnable.class.isAssignableFrom(entrypoint), "BlueMap only runs entrypoints implementing Runnable");
        assertDoesNotThrow(
                () -> entrypoint.getDeclaredConstructor().newInstance(),
                "BlueMap instantiates the entrypoint with a no-arg constructor");
    }
}
