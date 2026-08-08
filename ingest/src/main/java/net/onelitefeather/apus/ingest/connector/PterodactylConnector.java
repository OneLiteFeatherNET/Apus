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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * A pull source backed by a <a href="https://pterodactyl.io">Pterodactyl</a> game panel's Client
 * API. Backups are listed and downloaded exactly the way the Pterodactyl panel's own web UI does:
 * {@code GET /api/client/servers/{server}/backups} for the list, then {@code GET
 * /api/client/servers/{server}/backups/{backup}/download} for a short-lived signed URL to the
 * actual archive.
 *
 * <p><b>API surface, and where it comes from:</b> Pterodactyl's public docs (mirrored under
 * pteroapi.com / mintlify) confirm the endpoint shapes, but to pin down exact field names and
 * response bodies this implementation was checked directly against the Pterodactyl panel source
 * (github.com/pterodactyl/panel, {@code develop} branch, 2026-08-08):
 *
 * <ul>
 *   <li>{@code routes/api-client.php} -- the {@code /servers/{server}/backups} route group:
 *       {@code GET /} (list), {@code GET /{backup}} (view), {@code GET /{backup}/download}
 *       (signed URL), plus {@code POST /}, {@code POST /{backup}/lock}, {@code POST
 *       /{backup}/restore}, {@code DELETE /{backup}} which this connector does not use.
 *   <li>{@code app/Http/Controllers/Api/Client/Servers/BackupController.php} -- {@code
 *       index()} paginates with a {@code per_page} query parameter capped at 50 server-side;
 *       {@code download()} returns {@code new JsonResponse(['object' => 'signed_url',
 *       'attributes' => ['url' => $url]])} verbatim, requires the {@code ACTION_BACKUP_DOWNLOAD}
 *       permission, and only works for backups on the {@code wings} or {@code s3} storage
 *       adapter.
 *   <li>{@code app/Transformers/Api/Client/BackupTransformer.php} -- the exact attribute set
 *       returned per backup: {@code uuid}, {@code is_successful}, {@code is_locked}, {@code
 *       name}, {@code ignored_files}, {@code checksum}, {@code bytes}, {@code created_at}
 *       (ISO-8601), {@code completed_at} (ISO-8601 or {@code null}).
 *   <li>{@code app/Models/ApiKey.php} + {@code app/Providers/AuthServiceProvider.php} -- client
 *       API keys are Laravel Sanctum personal access tokens prefixed {@code ptlc_}, sent as
 *       {@code Authorization: Bearer <key>}.
 * </ul>
 *
 * <p>The one piece taken from the community docs mirrors rather than the source directly is the
 * exact shape of the list endpoint's outer envelope ({@code {"object":"list","data":[...],
 * "meta":{"pagination":{...},"backup_count":N}}}) -- this is produced by Pterodactyl's shared
 * Fractal serializer, which was not located in this pass, but the envelope is consistent across
 * every list endpoint documented for the panel and matches what the mirrors show. If a real panel
 * ever disagrees with this envelope, treat that as the fact and this comment as stale.
 */
public final class PterodactylConnector implements WorldSourceConnector {

    public static final String CONFIG_PANEL_URL = "panelUrl";
    public static final String CONFIG_SERVER_ID = "serverId";
    public static final String CONFIG_API_KEY = "apiKey";

    /** Comma-separated top-level archive paths that make up "the world directory". */
    public static final String CONFIG_WORLD_PATHS = "worldPaths";

    private final HttpClient httpClient;

    public PterodactylConnector() {
        this(HttpClient.newHttpClient());
    }

    /** Visible for tests to inject a client with tighter timeouts against a local HTTP stub. */
    PterodactylConnector(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String type() {
        return "pterodactyl";
    }

    @Override
    public List<SourceVersion> discover(Map<String, String> config) {
        String panelUrl = require(config, CONFIG_PANEL_URL);
        String serverId = require(config, CONFIG_SERVER_ID);
        String apiKey = require(config, CONFIG_API_KEY);

        URI uri = URI.create(trimTrailingSlash(panelUrl) + "/api/client/servers/" + serverId + "/backups?per_page=50");
        HttpResponse<String> response = sendForString(authorizedRequest(uri, apiKey));
        requireSuccess(response, "list backups");

        Map<String, Object> root = MinimalJson.asMap(MinimalJson.parse(response.body()));
        List<Object> data = MinimalJson.asList(root.get("data"));

        List<SourceVersion> versions = new ArrayList<>();
        for (Object item : data) {
            Map<String, Object> attributes = MinimalJson.asMap(MinimalJson.asMap(item).get("attributes"));
            if (!Boolean.TRUE.equals(attributes.get("is_successful"))) {
                // A backup still running or that failed has nothing fetchable yet.
                continue;
            }
            String uuid = (String) attributes.get("uuid");
            String name = (String) attributes.get("name");
            String createdAt = (String) attributes.get("created_at");
            long bytes = MinimalJson.asLong(attributes.get("bytes"));
            versions.add(new SourceVersion(uuid, name, OffsetDateTime.parse(createdAt).toInstant(), bytes));
        }
        return versions;
    }

    @Override
    public void fetch(Map<String, String> config, SourceVersion version, Path workDir) {
        String panelUrl = require(config, CONFIG_PANEL_URL);
        String serverId = require(config, CONFIG_SERVER_ID);
        String apiKey = require(config, CONFIG_API_KEY);
        Set<String> worldPaths = parseWorldPaths(config);

        URI downloadUri = URI.create(trimTrailingSlash(panelUrl) + "/api/client/servers/" + serverId + "/backups/"
                + version.id() + "/download");
        HttpResponse<String> signed = sendForString(authorizedRequest(downloadUri, apiKey));
        requireSuccess(signed, "request signed backup download url");

        Map<String, Object> body = MinimalJson.asMap(MinimalJson.parse(signed.body()));
        Object url = MinimalJson.asMap(body.get("attributes")).get("url");
        if (!(url instanceof String signedUrl)) {
            throw new IllegalStateException("Pterodactyl signed_url response had no attributes.url: " + signed.body());
        }

        // The backup is a tar.gz of the entire server -- plugins, configs and worlds mixed
        // together, potentially tens of gigabytes. gzip is not seekable, so the stream is walked
        // exactly once and only entries under the configured world paths are written; the archive
        // as a whole is never buffered in memory or written to disk.
        HttpRequest downloadRequest =
                HttpRequest.newBuilder(URI.create(signedUrl)).GET().build();
        try {
            HttpResponse<InputStream> archive =
                    httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (archive.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "downloading the backup archive failed with HTTP " + archive.statusCode());
            }
            try (InputStream raw = archive.body();
                    GZIPInputStream gzip = new GZIPInputStream(raw)) {
                Archives.extractTar(gzip, workDir, entryName -> matchesAnyWorldPath(entryName, worldPaths));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("failed to stream backup archive from " + signedUrl, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while downloading backup archive", e);
        }
    }

    private HttpResponse<String> sendForString(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Pterodactyl API request failed: " + request.uri(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted during Pterodactyl API request: " + request.uri(), e);
        }
    }

    private static HttpRequest authorizedRequest(URI uri, String apiKey) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .GET()
                .build();
    }

    private static void requireSuccess(HttpResponse<String> response, String action) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Pterodactyl API call to " + action + " failed with HTTP "
                    + response.statusCode() + ": " + response.body());
        }
    }

    private static boolean matchesAnyWorldPath(String entryName, Set<String> worldPaths) {
        for (String worldPath : worldPaths) {
            if (entryName.equals(worldPath) || entryName.startsWith(worldPath + "/")) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> parseWorldPaths(Map<String, String> config) {
        String raw = require(config, CONFIG_WORLD_PATHS);
        Set<String> paths = new LinkedHashSet<>();
        for (String path : raw.split(",")) {
            String trimmed = path.trim();
            if (!trimmed.isEmpty()) {
                paths.add(trimmed);
            }
        }
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("Pterodactyl source config " + CONFIG_WORLD_PATHS + " must list at least one path");
        }
        return paths;
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String require(Map<String, String> config, String key) {
        String value = config.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required Pterodactyl source config key: " + key);
        }
        return value;
    }
}
