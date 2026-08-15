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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    /**
     * <b>Nothing logged here may carry a credential.</b> This connector holds two: the panel API
     * key (a {@code ptlc_} token sent as a bearer header) and the short-lived signed download URL,
     * whose query string <em>is</em> the authorisation to download the whole backup. Neither the
     * key, the signed URL, nor any response body that could contain the signed URL goes into a log
     * line, a span attribute or an exception message -- see {@code docs/logging-and-tracing.md}
     * and the design spec's §12 rule for CR status and events. The panel host and the backup UUID
     * are enough to diagnose every failure this class can produce.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(PterodactylConnector.class);

    public static final String CONFIG_PANEL_URL = "panelUrl";
    public static final String CONFIG_SERVER_ID = "serverId";
    public static final String CONFIG_API_KEY = "apiKey";

    /** Comma-separated top-level archive paths that make up "the world directory". */
    public static final String CONFIG_WORLD_PATHS = "worldPaths";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Applied both as the connect timeout (via {@link HttpClient.Builder#connectTimeout}) and as
     * the per-request timeout (via {@link HttpRequest.Builder#timeout}) on every call this
     * connector makes. Without either, a panel that accepts a TCP connection and then simply
     * never responds -- or never finishes sending a large backup body -- would hang the calling
     * thread indefinitely. {@code discover()} runs directly inside {@code WorldSourceReconciler},
     * whose JOSDK worker pool is shared across all five reconcilers (see the class Javadoc), so an
     * unresponsive panel would starve unrelated render/ingest reconciliation too, not just this
     * source's own polling.
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;

    public PterodactylConnector() {
        this(HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build());
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
        LOGGER.debug("listing backups of Pterodactyl server {} on {}", serverId, uri.getHost());
        HttpResponse<String> response = sendForString(authorizedRequest(uri, apiKey));
        requireSuccess(response, "list backups");

        JsonNode root = parseJson(response.body());
        requireListEnvelope(root, response.body());

        List<SourceVersion> versions = new ArrayList<>();
        for (JsonNode item : root.path("data")) {
            JsonNode attributes = item.path("attributes");
            if (!attributes.path("is_successful").asBoolean(false)) {
                // A backup still running or that failed has nothing fetchable yet.
                LOGGER.debug("skipping backup {}: not successful (yet)", attributes.path("uuid").asText("<unknown>"));
                continue;
            }
            String uuid = attributes.path("uuid").asText(null);
            String name = attributes.path("name").asText(null);
            String createdAt = attributes.path("created_at").asText(null);
            long bytes = attributes.path("bytes").asLong();
            versions.add(new SourceVersion(uuid, name, OffsetDateTime.parse(createdAt).toInstant(), bytes));
        }
        LOGGER.info("discovered {} fetchable backup(s) on Pterodactyl server {}", versions.size(), serverId);
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
        LOGGER.info("fetching backup {} of Pterodactyl server {}", version.id(), serverId);
        HttpResponse<String> signed = sendForString(authorizedRequest(downloadUri, apiKey));
        // Deliberately body-free, unlike the list call below: this endpoint's body is where the
        // signed URL lives, and an unexpected shape is precisely the case where it would end up in
        // the message.
        requireSuccessWithoutBody(signed, "request signed backup download url");

        JsonNode urlNode = parseJsonWithoutBody(signed.body(), "signed backup download url")
                .path("attributes")
                .path("url");
        if (!urlNode.isTextual()) {
            throw new IllegalStateException(
                    "Pterodactyl signed_url response had no textual attributes.url (body withheld: it may contain the"
                            + " signed URL itself)");
        }
        String signedUrl = urlNode.asText();

        // The backup is a tar.gz of the entire server -- plugins, configs and worlds mixed
        // together, potentially tens of gigabytes. gzip is not seekable, so the stream is walked
        // exactly once and only entries under the configured world paths are written; the archive
        // as a whole is never buffered in memory or written to disk.
        URI signedUri = URI.create(signedUrl);
        HttpRequest downloadRequest =
                HttpRequest.newBuilder(signedUri).timeout(REQUEST_TIMEOUT).GET().build();
        // The host, never the URI: everything after it is the signature.
        LOGGER.info("streaming backup {} from host {} into {}", version.id(), signedUri.getHost(), workDir);
        try {
            HttpResponse<InputStream> archive =
                    httpClient.send(downloadRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (archive.statusCode() / 100 != 2) {
                throw new IllegalStateException(
                        "downloading the backup archive failed with HTTP " + archive.statusCode());
            }
            try (InputStream raw = archive.body();
                    GZIPInputStream gzip = new GZIPInputStream(raw)) {
                Archives.extractTar(
                        gzip, workDir, entryName -> matchesAnyWorldPath(entryName, worldPaths), Archives.limitsFrom(config));
            }
        } catch (IOException e) {
            // Never embed the signed URL itself in the message: it is a short-lived but
            // fully-privileged credential for downloading the entire backup, and exception
            // messages end up on stderr and therefore in log aggregation (see IngestMain). The
            // host alone is enough to diagnose a connectivity problem.
            throw new UncheckedIOException("failed to stream backup archive from host " + signedUri.getHost(), e);
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

    private static JsonNode parseJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException("failed to parse Pterodactyl API response as JSON: " + body, e);
        }
    }

    /**
     * Same as {@link #parseJson}, for a response whose body may contain a credential -- the
     * signed-URL endpoint's does. {@code action} names the call instead.
     */
    private static JsonNode parseJsonWithoutBody(String body, String action) {
        try {
            return MAPPER.readTree(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(
                    "failed to parse the Pterodactyl " + action + " response as JSON (body withheld: it may contain a"
                            + " credential)",
                    e);
        }
    }

    private static HttpRequest authorizedRequest(URI uri, String apiKey) {
        return HttpRequest.newBuilder(uri)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
    }

    private static void requireSuccess(HttpResponse<String> response, String action) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("Pterodactyl API call to " + action + " failed with HTTP "
                    + response.statusCode() + ": " + response.body());
        }
    }

    /**
     * Same as {@link #requireSuccess}, minus the body -- for the signed-URL endpoint, whose
     * responses are the one place a Pterodactyl body can carry a credential.
     */
    private static void requireSuccessWithoutBody(HttpResponse<String> response, String action) {
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                    "Pterodactyl API call to " + action + " failed with HTTP " + response.statusCode());
        }
    }

    /**
     * Rejects a backups-list response that does not match the documented list envelope ({@code
     * {"object":"list","data":[...]}} -- see the class Javadoc's "one piece taken from community
     * docs" note) instead of silently falling through.
     *
     * <p>Without this check, a panel that answers with an unexpected shape -- a different API
     * version, a proxy's error page returned with a 2xx status, a permission response that omits
     * {@code data} -- would make {@code root.path("data")} resolve to a Jackson {@code
     * MissingNode}, which iterates as empty. That reads as "the source has zero backups", a
     * perfectly healthy, reportable state ({@code WorldSourceReconciler}'s {@code UP_TO_DATE}
     * condition) -- turning a genuinely unverified assumption about the response shape into a
     * silent, permanent misdiagnosis instead of a visible, retried failure.
     */
    private static void requireListEnvelope(JsonNode root, String rawBody) {
        if (!"list".equals(root.path("object").asText(null)) || !root.path("data").isArray()) {
            throw new IllegalStateException(
                    "Pterodactyl backups response did not match the expected {object:\"list\",data:[...]} envelope: "
                            + rawBody);
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
