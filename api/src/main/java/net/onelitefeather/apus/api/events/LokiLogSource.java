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
package net.onelitefeather.apus.api.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Preferred {@link LogSource} (design spec §11.1): reads log lines out of Loki, which Alloy
 * already fills with every pod's logs cluster-wide, instead of the API talking to pods directly.
 * Chosen over the Kubernetes-client fallback by {@link LogSourceFactory} whenever a Loki base URL
 * is configured -- see that class and the task 3 report for the full trade-off.
 *
 * <p>Uses {@code java.net.http.HttpClient} (JDK-provided, no extra compile dependency -- see
 * {@link SseSource}'s Javadoc for why avoiding new dependencies matters in this module right
 * now) to poll Loki's {@code query_range} HTTP endpoint every {@link #POLL_INTERVAL}, advancing
 * the queried window past the last timestamp seen each round. Loki also exposes a push-based
 * {@code /loki/api/v1/tail} WebSocket endpoint, which would avoid polling entirely and pairs
 * naturally with {@code java.net.http.HttpClient}'s built-in WebSocket support (no extra
 * dependency needed there either) -- left for a follow-up: {@code query_range} is enough to
 * ship the feature and is far simpler to reason about and to unit-test ({@link
 * #parseStreams(String)} needs no live server), and this class is the one place that would need
 * to change to switch to it.
 *
 * <p><b>Label assumption, unverified against the actual cluster:</b> the LogQL selector below
 * assumes Alloy exposes the standard Kubernetes pod-discovery labels {@code namespace} and
 * {@code pod} (the common Alloy/promtail convention). The design spec does not pin this down
 * ("gefiltert auf den Job des jeweiligen Renders", §11.1, without saying how) -- confirm against
 * the real Alloy scrape config before this is exercised against a live cluster.
 */
final class LokiLogSource implements LogSource {

    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);

    /** Replayed once at subscribe time, so a viewer opening the stream mid-render sees recent context. */
    private static final Duration INITIAL_LOOKBACK = Duration.ofMinutes(5);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final URI baseUri;
    private final HttpClient httpClient;

    LokiLogSource(URI baseUri) {
        this.baseUri = baseUri;
        this.httpClient = HttpClient.newHttpClient();
    }

    @Override
    public AutoCloseable tail(String namespace, String jobName, SseSource.Sink<String> sink) {
        String query = "{namespace=\"" + namespace + "\", pod=~\"" + jobName + ".*\"}";
        Thread poller =
                Thread.ofVirtual().name("loki-tail-" + jobName).start(() -> poll(query, sink));
        return poller::interrupt;
    }

    private void poll(String query, SseSource.Sink<String> sink) {
        long startNanos = (System.currentTimeMillis() - INITIAL_LOOKBACK.toMillis()) * 1_000_000L;
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long endNanos = System.currentTimeMillis() * 1_000_000L;
                List<LogLine> lines = queryRange(query, startNanos, endNanos);
                for (LogLine line : lines) {
                    sink.next(line.text());
                    startNanos = Math.max(startNanos, line.timestampNanos() + 1);
                }
                Thread.sleep(POLL_INTERVAL);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // Includes the cancelled-by-interrupt case where HttpClient.send surfaces the
            // interruption as something other than InterruptedException; sink is a no-op past
            // stream end either way (SseSource.SingleSubscription.done), so reporting it here
            // even after cancellation is harmless.
            sink.error(e);
            return;
        }
        sink.complete();
    }

    private List<LogLine> queryRange(String query, long startNanos, long endNanos)
            throws IOException, InterruptedException {
        URI uri = URI.create(baseUri + "/loki/api/v1/query_range?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8) + "&start=" + startNanos + "&end=" + endNanos
                + "&direction=forward&limit=1000");
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Loki query_range returned HTTP " + response.statusCode() + ": " + response.body());
        }
        return parseStreams(response.body());
    }

    /**
     * Parses a Loki {@code query_range} response body into ordered log lines. Package-private
     * and static so it can be unit-tested against a canned response body without a live Loki
     * instance -- see {@code LokiLogSourceTest}.
     */
    static List<LogLine> parseStreams(String json) throws IOException {
        JsonNode root = MAPPER.readTree(json);
        List<LogLine> lines = new ArrayList<>();
        for (JsonNode stream : root.path("data").path("result")) {
            for (JsonNode value : stream.path("values")) {
                lines.add(new LogLine(Long.parseLong(value.get(0).asText()), value.get(1).asText()));
            }
        }
        lines.sort(Comparator.comparingLong(LogLine::timestampNanos));
        return lines;
    }

    record LogLine(long timestampNanos, String text) {}
}
