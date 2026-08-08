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
package net.onelitefeather.apus.ingest.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link PterodactylConnector} against a local HTTP stub that reproduces the Pterodactyl
 * Client API's documented backup endpoints and response shapes (see the connector's class Javadoc
 * for exactly which parts are sourced from the panel's own code vs. community docs). No real panel
 * is contacted, and the stub binds to the loopback address only.
 */
class PterodactylConnectorTest {

    private static final String SERVER_ID = "srv-1";
    private static final String API_KEY = "ptlc_test_key";
    private static final String SUCCESSFUL_BACKUP_UUID = "11111111-1111-1111-1111-111111111111";

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void discoverListsOnlySuccessfulBackupsAndSendsTheBearerToken() throws IOException {
        startServer(null);

        List<SourceVersion> versions = new PterodactylConnector().discover(baseConfig());

        assertEquals(1, versions.size(), "the still-running backup must be filtered out");
        SourceVersion version = versions.get(0);
        assertEquals(SUCCESSFUL_BACKUP_UUID, version.id());
        assertEquals("daily-backup", version.label());
        assertEquals(123456L, version.sizeBytes());
        assertEquals(Instant.parse("2026-08-01T10:00:00Z"), version.createdAt());
    }

    /**
     * The core requirement this task exists to prove: the backup is a tar.gz of the *entire*
     * server (plugins, configs, worlds all mixed together), gzip is not seekable, so the stream is
     * walked exactly once and only the configured world paths are written to disk -- nothing else
     * from the archive ever lands in the work directory.
     */
    @Test
    void fetchExtractsOnlyWorldPathsFromAMixedServerBackupInOnePass(@TempDir Path workDir) throws IOException {
        byte[] mixedBackup = new TestTarBuilder()
                .addFile("server.properties", "level-name=world")
                .addDirectory("plugins")
                .addFile("plugins/Essentials/config.yml", "locale: en")
                .addFile("plugins/Essentials/userdata/uuid.yml", "balance: 100")
                .addDirectory("logs")
                .addFile("logs/latest.log", "[INFO] server started")
                .addFile("world/level.dat", "overworld-level-data")
                .addFile("world/region/r.0.0.mca", "overworld-region-data")
                .addFile("world_nether/DIM-1/region/r.0.0.mca", "nether-region-data")
                .toGzippedTarBytes();
        startServer(mixedBackup);

        Map<String, String> config = baseConfig();
        config.put(PterodactylConnector.CONFIG_WORLD_PATHS, "world,world_nether");
        SourceVersion version = new SourceVersion(SUCCESSFUL_BACKUP_UUID, "daily-backup", Instant.now(), mixedBackup.length);

        new PterodactylConnector().fetch(config, version, workDir);

        assertEquals("overworld-level-data", Files.readString(workDir.resolve("world/level.dat")));
        assertEquals("overworld-region-data", Files.readString(workDir.resolve("world/region/r.0.0.mca")));
        assertEquals(
                "nether-region-data", Files.readString(workDir.resolve("world_nether/DIM-1/region/r.0.0.mca")));

        assertFalse(Files.exists(workDir.resolve("server.properties")), "server.properties is not part of a world");
        assertFalse(Files.exists(workDir.resolve("plugins")), "plugin data is not part of a world");
        assertFalse(Files.exists(workDir.resolve("logs")), "log files are not part of a world");
    }

    private Map<String, String> baseConfig() {
        Map<String, String> config = new HashMap<>();
        config.put(PterodactylConnector.CONFIG_PANEL_URL, "http://127.0.0.1:" + server.getAddress().getPort());
        config.put(PterodactylConnector.CONFIG_SERVER_ID, SERVER_ID);
        config.put(PterodactylConnector.CONFIG_API_KEY, API_KEY);
        return config;
    }

    /**
     * Starts the panel + download stub on the loopback address only. When {@code archiveBytes} is
     * non-null, a third endpoint serves it as the signed-URL download target.
     */
    private void startServer(byte[] archiveBytes) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);

        server.createContext("/api/client/servers/" + SERVER_ID + "/backups", exchange -> {
            if (!("/api/client/servers/" + SERVER_ID + "/backups").equals(exchange.getRequestURI().getPath())) {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            requireBearerToken(exchange);
            respondJson(exchange, 200, backupsListResponse());
        });

        server.createContext(
                "/api/client/servers/" + SERVER_ID + "/backups/" + SUCCESSFUL_BACKUP_UUID + "/download", exchange -> {
                    requireBearerToken(exchange);
                    String archiveUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/archive.tar.gz";
                    respondJson(
                            exchange,
                            200,
                            "{\"object\":\"signed_url\",\"attributes\":{\"url\":\"" + archiveUrl + "\"}}");
                });

        if (archiveBytes != null) {
            server.createContext("/archive.tar.gz", exchange -> {
                exchange.getResponseHeaders().add("Content-Type", "application/gzip");
                exchange.sendResponseHeaders(200, archiveBytes.length);
                try (var out = exchange.getResponseBody()) {
                    out.write(archiveBytes);
                }
            });
        }

        server.start();
    }

    private static void requireBearerToken(HttpExchange exchange) throws IOException {
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (!("Bearer " + API_KEY).equals(authorization)) {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            throw new IOException("unauthorized stub request, aborting handler");
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Response shape verified against the Pterodactyl panel source directly (see {@link
     * PterodactylConnector}'s class Javadoc): {@code BackupTransformer} attribute names, and
     * {@code BackupController::index}'s pagination wrapper as documented by the panel's API docs
     * mirrors.
     */
    private static String backupsListResponse() {
        return """
                {
                  "object": "list",
                  "data": [
                    {
                      "object": "backup",
                      "attributes": {
                        "uuid": "%s",
                        "is_successful": true,
                        "is_locked": false,
                        "name": "daily-backup",
                        "ignored_files": [],
                        "checksum": "sha256:aaaabbbbcccc",
                        "bytes": 123456,
                        "created_at": "2026-08-01T10:00:00+00:00",
                        "completed_at": "2026-08-01T10:05:00+00:00"
                      }
                    },
                    {
                      "object": "backup",
                      "attributes": {
                        "uuid": "22222222-2222-2222-2222-222222222222",
                        "is_successful": false,
                        "is_locked": false,
                        "name": "still-running-backup",
                        "ignored_files": [],
                        "checksum": null,
                        "bytes": 0,
                        "created_at": "2026-08-02T10:00:00+00:00",
                        "completed_at": null
                      }
                    }
                  ],
                  "meta": {
                    "pagination": {
                      "total": 2,
                      "count": 2,
                      "per_page": 50,
                      "current_page": 1,
                      "total_pages": 1
                    },
                    "backup_count": 2
                  }
                }
                """
                .formatted(SUCCESSFUL_BACKUP_UUID);
    }
}
