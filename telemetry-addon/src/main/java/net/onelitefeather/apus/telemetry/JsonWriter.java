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

import java.util.Locale;

/**
 * Serialises a {@link ProgressSnapshot} to JSON without pulling in a JSON library.
 *
 * <p>The addon runs in its own classloader next to BlueMap; every shipped dependency
 * is a potential conflict. The payload is a flat object of eight known fields, so a
 * hand-written writer is both sufficient and safer than a dependency.
 */
public final class JsonWriter {

    private JsonWriter() {}

    public static String toJson(ProgressSnapshot snapshot) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        appendString(out, "state", snapshot.state().name().toLowerCase(Locale.ROOT));
        out.append(',');
        appendString(out, "currentMap", snapshot.currentMap());
        out.append(',');
        appendNumber(out, "progress", snapshot.progress());
        out.append(',');
        out.append("\"etaSeconds\":").append(snapshot.etaSeconds());
        out.append(',');
        out.append("\"queuedTasks\":").append(snapshot.queuedTasks());
        out.append(',');
        out.append("\"renderThreads\":").append(snapshot.renderThreads());
        out.append(',');
        out.append("\"degraded\":").append(snapshot.degraded());
        out.append(',');
        appendString(out, "description", snapshot.description());
        out.append('}');
        return out.toString();
    }

    private static void appendString(StringBuilder out, String key, String value) {
        out.append('"').append(key).append("\":");
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        escape(out, value);
        out.append('"');
    }

    private static void appendNumber(StringBuilder out, String key, double value) {
        out.append('"').append(key).append("\":").append(Numbers.compact(value));
    }

    private static void escape(StringBuilder out, String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
    }
}
