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
package net.onelitefeather.apus.operator.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the fine-grained progress {@code WorldIngestReconciler}'s coarse Job/Pod-status polling
 * cannot see out of the ingest pod's own log lines -- exactly the fallback {@code
 * ingest/README.md}'s "Progress reporting" design note anticipates: {@code IngestMain} prints
 * stable, greppable {@code phase=<...>} and {@code progress: NN.N% (done/total bytes)} lines
 * instead of running an HTTP server, precisely so a reconciler can read them back out of {@code
 * kubectl logs} equivalent.
 *
 * <p>Best-effort by construction: every field is {@code null} (or an empty list) when the
 * corresponding line was never found, e.g. because the pod has not logged anything yet, or the
 * pod could not be reached at all. Callers must treat an all-{@code null} result the same as "no
 * new information", never as an error.
 */
public record IngestLogProgress(String phase, Double percent, Long bytesDone, Long bytesTotal, List<String> dimensions) {

    private static final Pattern PHASE = Pattern.compile("phase=([A-Za-z]+)");
    private static final Pattern PROGRESS = Pattern.compile("progress: (\\d+(?:\\.\\d+)?)% \\((\\d+)/(\\d+) bytes\\)");
    private static final Pattern DIMENSIONS = Pattern.compile("dimensions=\\[(.*?)]");

    /** Scans {@code log} for the last occurrence of each recognised line, in whatever order they appear. */
    public static IngestLogProgress parse(String log) {
        if (log == null || log.isBlank()) {
            return new IngestLogProgress(null, null, null, null, List.of());
        }

        String phase = lastMatch(PHASE, log, 1);

        Double percent = null;
        Long bytesDone = null;
        Long bytesTotal = null;
        Matcher progressMatcher = PROGRESS.matcher(log);
        while (progressMatcher.find()) {
            percent = Double.valueOf(progressMatcher.group(1));
            bytesDone = Long.valueOf(progressMatcher.group(2));
            bytesTotal = Long.valueOf(progressMatcher.group(3));
        }

        List<String> dimensions = List.of();
        String dimensionsGroup = lastMatch(DIMENSIONS, log, 1);
        if (dimensionsGroup != null && !dimensionsGroup.isBlank()) {
            List<String> parsed = new ArrayList<>();
            for (String part : dimensionsGroup.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(trimmed);
                }
            }
            dimensions = parsed;
        }

        return new IngestLogProgress(phase, percent, bytesDone, bytesTotal, dimensions);
    }

    private static String lastMatch(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        String last = null;
        while (matcher.find()) {
            last = matcher.group(group);
        }
        return last;
    }
}
