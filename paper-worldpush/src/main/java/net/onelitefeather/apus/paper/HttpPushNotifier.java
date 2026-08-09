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
package net.onelitefeather.apus.paper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Real {@link PushNotifier}, calling {@code POST {api-base-url}/api/push/{push-token}} (design
 * spec §11.1) with the JDK's built-in {@link HttpClient} -- no need for a heavier HTTP dependency
 * for one small POST call.
 *
 * <p>The push token is part of the request path, as the API contract in the design spec defines
 * it. That means it is unavoidably present in the {@link URI} object built here -- but that URI
 * is never logged, printed, or included in an exception message; only the host and a fixed,
 * token-free label are. A future revision of the API that moves the token into an {@code
 * Authorization} header instead would only need to change {@link #buildRequest}.
 */
public final class HttpPushNotifier implements PushNotifier {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final URI apiBaseUrl;
    private final String pushToken;

    public HttpPushNotifier(URI apiBaseUrl, String pushToken) {
        this.httpClient = HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build();
        this.apiBaseUrl = apiBaseUrl;
        this.pushToken = pushToken;
    }

    @Override
    public void notifyPushComplete(PushSummary summary) {
        HttpRequest request = buildRequest(summary);
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new PushNotificationException(
                    "Could not reach the Apus API at " + apiBaseUrl.getHost() + " to report a completed push.", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PushNotificationException("Interrupted while reporting a completed push.", e);
        }

        if (response.statusCode() / 100 != 2) {
            // Deliberately no response body in the message: it echoes request content back and
            // this method has no way to know the API never includes the token in it.
            throw new PushNotificationException("Apus API at " + apiBaseUrl.getHost()
                    + " rejected the push completion report with HTTP " + response.statusCode() + ".");
        }
    }

    private HttpRequest buildRequest(PushSummary summary) {
        URI target = apiBaseUrl.resolve("/api/push/" + pushToken);
        String body = "{\"tenant\":\"" + jsonEscape(summary.tenant()) + "\",\"worldName\":\""
                + jsonEscape(summary.worldName()) + "\",\"fileCount\":" + summary.fileCount() + ",\"bytesUploaded\":"
                + summary.bytesUploaded() + "}";
        return HttpRequest.newBuilder(target)
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Thrown when the completion report could not be delivered or was rejected by the API. */
    public static final class PushNotificationException extends RuntimeException {

        PushNotificationException(String message) {
            super(message);
        }

        PushNotificationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
