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
package net.onelitefeather.apus.api.policy;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The five types a policy entry's value may declare.
 *
 * <p>The wire names are stored inside {@code Tenant} manifests in Git; renaming one silently
 * reinterprets every entry already written with it, so {@code PolicyTypeTest} pins them.
 *
 * <p>Durations use Go's spelling ({@code 5m}, {@code 1h30m}) rather than ISO-8601, because that
 * is what {@code WorldSourceSpec.poll} already uses and what a tenant types into the source form.
 * A policy that spoke a different dialect could not be compared with the value it governs.
 */
public enum PolicyType {
    STRING("string"),
    INTEGER("integer"),
    BOOLEAN("boolean"),
    DURATION("duration"),
    STRING_LIST("stringList");

    /** Every group optional, so the empty string matches -- guarded against in {@link #accepts}. */
    private static final Pattern DURATION_PATTERN = Pattern.compile("^(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?$");

    private final String wireName;

    PolicyType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<PolicyType> fromWireName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(type -> type.wireName.equals(name)).findFirst();
    }

    /** Whether {@code value} can be interpreted as this type. Never throws. */
    public boolean accepts(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return switch (this) {
            case STRING, STRING_LIST -> true;
            case INTEGER -> {
                try {
                    Long.parseLong(trimmed);
                    yield true;
                } catch (NumberFormatException notANumber) {
                    yield false;
                }
            }
            // Not Boolean.parseBoolean: it answers false for "yes" as readily as for "false", so
            // an administrator's typo would become the opposite of what they meant.
            case BOOLEAN -> "true".equals(trimmed) || "false".equals(trimmed);
            case DURATION -> {
                Matcher matcher = DURATION_PATTERN.matcher(trimmed);
                // Every group is optional, so the pattern also matches "" and would accept a
                // bare "5" as zero. At least one unit has to be present.
                yield matcher.matches()
                        && (matcher.group(1) != null || matcher.group(2) != null || matcher.group(3) != null);
            }
        };
    }

    public long parseInteger(String value) {
        return Long.parseLong(value.trim());
    }

    public boolean parseBoolean(String value) {
        return "true".equals(value.trim());
    }

    public long parseDurationSeconds(String value) {
        Matcher matcher = DURATION_PATTERN.matcher(value.trim());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("not a duration: " + value);
        }
        long hours = matcher.group(1) == null ? 0 : Long.parseLong(matcher.group(1));
        long minutes = matcher.group(2) == null ? 0 : Long.parseLong(matcher.group(2));
        long seconds = matcher.group(3) == null ? 0 : Long.parseLong(matcher.group(3));
        return hours * 3600 + minutes * 60 + seconds;
    }

    public List<String> parseStringList(String value) {
        if (value.isBlank()) {
            // Deliberately a valid policy rather than a malformed one: "no source type is
            // allowed at all" is a rule someone may genuinely want to express.
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }
}
