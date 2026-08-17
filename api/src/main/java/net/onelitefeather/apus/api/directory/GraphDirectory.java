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
package net.onelitefeather.apus.api.directory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link Directory} backed by Microsoft Graph, running as the confidential app registration
 * described in {@link GraphDirectoryConfiguration}.
 *
 * <p>Plain {@code java.net.http} rather than the Graph SDK: the six operations in {@link
 * Directory} are the complete extent of what this platform does to the directory, and a reader
 * auditing a directory-wide permission grant should be able to see every request that grant
 * enables without following an SDK's abstractions. The token is fetched with the
 * client-credentials flow and cached until shortly before it expires.
 *
 * <p><b>Authorisation is not this class's job.</b> {@link DirectoryGuard} decides, and the
 * controller calls it before anything here runs. Repeating the checks here would invite the
 * belief that either place alone is sufficient.
 */
public class GraphDirectory implements Directory {

    private static final Logger LOGGER = LoggerFactory.getLogger(GraphDirectory.class);

    /** Refresh this far before the token actually expires, so a call never races the boundary. */
    private static final Duration TOKEN_EARLY_REFRESH = Duration.ofMinutes(5);

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final GraphDirectoryConfiguration config;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<CachedToken> token = new AtomicReference<>();
    private final SecureRandom random = new SecureRandom();

    public GraphDirectory(GraphDirectoryConfiguration config) {
        this.config = config;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public List<DirectoryTeam> teamsIn(String groupId) {
        JsonNode body = get("/groups/" + encode(groupId) + "/members/microsoft.graph.group"
                + "?$select=id,displayName&$count=true");
        List<DirectoryTeam> teams = new ArrayList<>();
        for (JsonNode node : GraphResponses.items(body)) {
            teams.add(GraphResponses.team(node));
        }
        return teams;
    }

    @Override
    public List<DirectoryUser> membersOf(String groupId) {
        JsonNode body = get("/groups/" + encode(groupId) + "/members/microsoft.graph.user"
                + "?$select=id,displayName,mail,userPrincipalName");
        List<DirectoryUser> users = new ArrayList<>();
        for (JsonNode node : GraphResponses.items(body)) {
            // Roles are not fetched per member here: one extra request per user would turn a
            // member list into a burst that Graph throttles. The list is a display; the guard
            // fetches roles for the one user an operation actually targets, in findUser.
            users.add(GraphResponses.user(node, Set.of()));
        }
        return users;
    }

    @Override
    public DirectoryTeam createTeam(String groupId, String displayName) {
        String nickname = mailNickname(displayName);
        String payload =
                """
                {"displayName":%s,"mailNickname":%s,"mailEnabled":false,"securityEnabled":true}"""
                        .formatted(quote(displayName), quote(nickname));
        JsonNode created = post("/groups", payload);
        String teamId = GraphResponses.text(created, "id");
        if (teamId.isBlank()) {
            throw new DirectoryUnavailableException("the directory created a group but returned no id");
        }
        // Nesting it under the tenant's group is what makes it *this tenant's* team rather than
        // a loose group in the directory -- and it is what every later read filters by.
        post(
                "/groups/" + encode(groupId) + "/members/$ref",
                """
                {"@odata.id":%s}"""
                        .formatted(quote(config.getGraphEndpoint() + "/directoryObjects/" + teamId)));
        return new DirectoryTeam(teamId, displayName, 0);
    }

    @Override
    public DirectoryUser invite(String groupId, String email, String displayName) {
        String payload =
                """
                {"invitedUserEmailAddress":%s,"invitedUserDisplayName":%s,\
                "inviteRedirectUrl":%s,"sendInvitationMessage":true}"""
                        .formatted(quote(email), quote(displayName), quote("https://myapps.microsoft.com"));
        JsonNode invitation = post("/invitations", payload);
        JsonNode invited = invitation.get("invitedUser");
        String userId = GraphResponses.text(invited, "id");
        if (userId.isBlank()) {
            throw new DirectoryUnavailableException("the directory accepted the invitation but returned no user");
        }
        post(
                "/groups/" + encode(groupId) + "/members/$ref",
                """
                {"@odata.id":%s}"""
                        .formatted(quote(config.getGraphEndpoint() + "/directoryObjects/" + userId)));
        return DirectoryUser.member(userId, displayName, email);
    }

    @Override
    public DirectoryUser findUser(String userId) {
        JsonNode body = get("/users/" + encode(userId) + "?$select=id,displayName,mail,userPrincipalName");
        if (body == null || GraphResponses.text(body, "id").isBlank()) {
            return null;
        }
        // Roles are fetched here and only here: this is the user an operation is about to act on,
        // and DirectoryGuard cannot decide about a password reset without knowing them.
        JsonNode roles = get("/users/" + encode(userId) + "/transitiveMemberOf?$select=id,displayName");
        return GraphResponses.user(body, GraphResponses.directoryRoles(roles));
    }

    @Override
    public String resetPassword(String userId) {
        String temporary = temporaryPassword();
        patch(
                "/users/" + encode(userId),
                """
                {"passwordProfile":{"password":%s,"forceChangePasswordNextSignIn":true}}"""
                        .formatted(quote(temporary)));
        // The password itself never reaches this log line, a span attribute or any resource
        // status -- the same rule the tenant push token follows.
        LOGGER.info("reset the password of directory user '{}'", userId);
        return temporary;
    }

    /**
     * A temporary password the user must replace at next sign-in. Base64 of 24 random bytes from
     * {@link SecureRandom}: comfortably past any complexity policy, and not something anyone is
     * expected to remember -- it is shown once and typed once.
     */
    private String temporaryPassword() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return "Ap!" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * A mail nickname Graph will accept: it rejects spaces and most punctuation, and a team named
     * "Map Builders" is otherwise a 400 rather than a group.
     */
    private static String mailNickname(String displayName) {
        String cleaned = displayName.replaceAll("[^A-Za-z0-9]", "").toLowerCase(java.util.Locale.ROOT);
        return cleaned.isBlank() ? "team" : cleaned;
    }

    // --- transport ------------------------------------------------------------------------------

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(URI.create(config.getGraphEndpoint() + path))
                .header("Authorization", "Bearer " + accessToken())
                .header("ConsistencyLevel", "eventual")
                .timeout(REQUEST_TIMEOUT)
                .GET());
    }

    private JsonNode post(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(config.getGraphEndpoint() + path))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    private JsonNode patch(String path, String body) {
        return send(HttpRequest.newBuilder(URI.create(config.getGraphEndpoint() + path))
                .header("Authorization", "Bearer " + accessToken())
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)));
    }

    /**
     * Sends a request and turns anything other than success into {@link
     * DirectoryUnavailableException}. A {@code 429} is retried once after the {@code Retry-After}
     * the service asked for, because Graph throttles routinely and a single retry converts most
     * of it into a slightly slower page rather than an error.
     */
    private JsonNode send(HttpRequest.Builder builder) {
        HttpResponse<String> response = exchange(builder);
        if (response.statusCode() == 429) {
            long wait = response.headers()
                    .firstValue("Retry-After")
                    .map(value -> {
                        try {
                            return Long.parseLong(value.trim());
                        } catch (NumberFormatException e) {
                            return 2L;
                        }
                    })
                    .orElse(2L);
            LOGGER.warn("the directory is throttling; retrying once in {}s", wait);
            try {
                Thread.sleep(Math.min(wait, 10) * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DirectoryUnavailableException("interrupted while waiting out directory throttling", e);
            }
            response = exchange(builder);
        }
        if (response.statusCode() == 404) {
            return null;
        }
        if (response.statusCode() >= 300) {
            // The body can carry the caller's own data back; only the status and Graph's own
            // error code go into the message.
            throw new DirectoryUnavailableException(
                    "the directory answered " + response.statusCode() + " (" + graphErrorCode(response.body()) + ")");
        }
        if (response.body() == null || response.body().isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(response.body());
        } catch (Exception e) {
            throw new DirectoryUnavailableException("the directory answered with something that is not JSON", e);
        }
    }

    private HttpResponse<String> exchange(HttpRequest.Builder builder) {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectoryUnavailableException("interrupted while talking to the directory", e);
        } catch (Exception e) {
            throw new DirectoryUnavailableException("could not reach the directory", e);
        }
    }

    /** Graph's own machine-readable error code, for a message that says something useful. */
    private String graphErrorCode(String body) {
        if (body == null || body.isBlank()) {
            return "no detail";
        }
        try {
            JsonNode error = mapper.readTree(body).get("error");
            String code = GraphResponses.text(error, "code");
            return code.isBlank() ? "no detail" : code;
        } catch (Exception e) {
            return "no detail";
        }
    }

    /** The app-only access token, fetched on first use and reused until close to its expiry. */
    private String accessToken() {
        CachedToken cached = token.get();
        if (cached != null && cached.isUsable()) {
            return cached.value();
        }
        String form = "client_id=" + encode(config.getClientId())
                + "&client_secret=" + encode(config.getClientSecret())
                + "&scope=" + encode("https://graph.microsoft.com/.default")
                + "&grant_type=client_credentials";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(config.getAuthority() + "/" + config.getTenantId() + "/oauth2/v2.0/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DirectoryUnavailableException("interrupted while acquiring a directory token", e);
        } catch (Exception e) {
            throw new DirectoryUnavailableException("could not reach the identity provider for a token", e);
        }
        if (response.statusCode() >= 300) {
            // Deliberately no body: a failed token response can echo the client_secret back.
            throw new DirectoryUnavailableException(
                    "the identity provider refused the directory credential (" + response.statusCode() + ")");
        }
        try {
            JsonNode body = mapper.readTree(response.body());
            String value = GraphResponses.text(body, "access_token");
            if (value.isBlank()) {
                throw new DirectoryUnavailableException("the identity provider returned no access token");
            }
            JsonNode expires = body.get("expires_in");
            long seconds = expires != null && expires.isNumber() ? expires.asLong() : 3600L;
            CachedToken fresh = new CachedToken(value, Instant.now().plusSeconds(seconds));
            token.set(fresh);
            return value;
        } catch (DirectoryUnavailableException e) {
            throw e;
        } catch (Exception e) {
            throw new DirectoryUnavailableException("could not read the identity provider's token response", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** JSON string literal, so a display name containing a quote cannot break the request. */
    private String quote(String value) {
        try {
            return mapper.writeValueAsString(value == null ? "" : value);
        } catch (Exception e) {
            throw new DirectoryUnavailableException("could not encode a value for the directory", e);
        }
    }

    private record CachedToken(String value, Instant expiresAt) {
        boolean isUsable() {
            return Instant.now().isBefore(expiresAt.minus(TOKEN_EARLY_REFRESH));
        }
    }
}
