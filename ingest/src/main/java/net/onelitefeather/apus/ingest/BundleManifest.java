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
package net.onelitefeather.apus.ingest;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The commit point of one version of a world bundle in S3: a versioned, self-describing
 * description of an ingested world.
 *
 * <p>A bundle is considered to exist only once its manifest object has been written -- see
 * {@link BundleWriter}, which writes it strictly after every region file it describes. A reader
 * that finds a manifest can therefore trust that every region file it lists is already present;
 * the absence of a manifest means the bundle version does not exist, regardless of what else may
 * have been left behind by an interrupted write.
 *
 * @param schemaVersion the manifest schema version, bumped whenever the JSON shape changes
 * @param tenant the owning tenant's identifier
 * @param worldId the world's identifier within the tenant
 * @param version this bundle version's identifier
 * @param source where the bundled world data came from
 * @param minecraftVersion the Minecraft version the world was generated/played under, or
 *     {@code null} if not known at bundle time
 * @param dimensions every dimension bundled, and the region files each one contains
 * @param sizeBytes the total size, in bytes, of every region file written for this bundle version
 * @param checksums a content checksum covering all region file bytes written for this bundle
 *     version
 */
public record BundleManifest(
        int schemaVersion,
        String tenant,
        String worldId,
        String version,
        SourceInfo source,
        String minecraftVersion,
        List<DimensionInfo> dimensions,
        long sizeBytes,
        Checksums checksums) {

    /**
     * Where the bundled world data came from.
     *
     * @param type the source connector type (e.g. {@code "s3"}, {@code "pterodactyl"}), or
     *     {@code null} if not known to the writer at bundle time
     * @param ref an identifier for the exact source version this bundle was produced from
     * @param detectedLayout the world layout kind (e.g. {@code "vanilla"} or {@code "bukkit"})
     *     that was detected for this world
     */
    public record SourceInfo(String type, String ref, String detectedLayout) {}

    /**
     * One dimension (overworld, the_nether, the_end, ...) inside the bundle, and the region
     * files it contains.
     *
     * @param id the logical dimension name (e.g. {@code "overworld"})
     * @param path the bundle-relative path this dimension's region files were written under
     * @param regions the {@code [x, z]} region coordinates present, read from each region file's
     *     {@code r.<x>.<z>.mca} name
     * @param regionCount {@code regions.size()}, kept alongside the list so consumers doing a
     *     quick count/progress check don't need to materialise it
     */
    public record DimensionInfo(String id, String path, List<int[]> regions, int regionCount) {}

    /**
     * A content checksum for the bundle.
     *
     * @param algorithm the digest algorithm used (e.g. {@code "SHA-256"})
     * @param manifest the hex-encoded digest covering all region file bytes written for this
     *     bundle version
     */
    public record Checksums(String algorithm, String manifest) {}

    /** Serialises this manifest to JSON. */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        Json.writeValue(sb, toMap());
        return sb.toString();
    }

    /**
     * Parses a manifest previously produced by {@link #toJson()}.
     *
     * @throws IllegalArgumentException if {@code json} is not a valid manifest document
     */
    public static BundleManifest fromJson(String json) {
        Object parsed = new Json.Parser(json).parse();
        if (!(parsed instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("Expected a JSON object at the manifest root");
        }
        Map<String, Object> map = asStringKeyedMap(root);
        return new BundleManifest(
                asInt(map.get("schemaVersion")),
                (String) map.get("tenant"),
                (String) map.get("worldId"),
                (String) map.get("version"),
                sourceInfoFromMap(asStringKeyedMap(map.get("source"))),
                (String) map.get("minecraftVersion"),
                dimensionsFromList((List<?>) map.get("dimensions")),
                asLong(map.get("sizeBytes")),
                checksumsFromMap(asStringKeyedMap(map.get("checksums"))));
    }

    private Map<String, Object> toMap() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", (long) schemaVersion);
        root.put("tenant", tenant);
        root.put("worldId", worldId);
        root.put("version", version);
        root.put("source", sourceToMap(source));
        root.put("minecraftVersion", minecraftVersion);
        root.put(
                "dimensions",
                dimensions.stream().map(BundleManifest::dimensionToMap).collect(Collectors.toList()));
        root.put("sizeBytes", sizeBytes);
        root.put("checksums", checksumsToMap(checksums));
        return root;
    }

    private static Map<String, Object> sourceToMap(SourceInfo source) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", source.type());
        map.put("ref", source.ref());
        map.put("detectedLayout", source.detectedLayout());
        return map;
    }

    private static SourceInfo sourceInfoFromMap(Map<String, Object> map) {
        return new SourceInfo(
                (String) map.get("type"), (String) map.get("ref"), (String) map.get("detectedLayout"));
    }

    private static Map<String, Object> dimensionToMap(DimensionInfo dimension) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", dimension.id());
        map.put("path", dimension.path());
        List<Object> regions = new ArrayList<>();
        for (int[] region : dimension.regions()) {
            regions.add(List.of((long) region[0], (long) region[1]));
        }
        map.put("regions", regions);
        map.put("regionCount", (long) dimension.regionCount());
        return map;
    }

    private static List<DimensionInfo> dimensionsFromList(List<?> raw) {
        List<DimensionInfo> result = new ArrayList<>();
        for (Object entry : raw) {
            Map<String, Object> map = asStringKeyedMap(entry);
            List<int[]> regions = new ArrayList<>();
            for (Object rawRegion : (List<?>) map.get("regions")) {
                List<?> pair = (List<?>) rawRegion;
                regions.add(new int[] {asInt(pair.get(0)), asInt(pair.get(1))});
            }
            result.add(new DimensionInfo((String) map.get("id"), (String) map.get("path"), regions, asInt(
                    map.get("regionCount"))));
        }
        return result;
    }

    private static Map<String, Object> checksumsToMap(Checksums checksums) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("algorithm", checksums.algorithm());
        map.put("manifest", checksums.manifest());
        return map;
    }

    private static Checksums checksumsFromMap(Map<String, Object> map) {
        return new Checksums((String) map.get("algorithm"), (String) map.get("manifest"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asStringKeyedMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected a JSON object, got: " + value);
        }
        return (Map<String, Object>) map;
    }

    private static int asInt(Object value) {
        return Math.toIntExact(asLong(value));
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("Expected a JSON number, got: " + value);
    }

    /**
     * A minimal, dependency-free JSON codec covering exactly the value shapes {@link
     * BundleManifest} needs: objects, arrays, strings, integral numbers, and {@code null}.
     */
    private static final class Json {

        private Json() {}

        static void writeValue(StringBuilder sb, Object value) {
            switch (value) {
                case null -> sb.append("null");
                case String s -> writeString(sb, s);
                case Boolean b -> sb.append(b);
                case Long l -> sb.append(l);
                case Integer i -> sb.append(i);
                case Map<?, ?> map -> writeObject(sb, map);
                case List<?> list -> writeArray(sb, list);
                default ->
                        throw new IllegalArgumentException(
                                "Unsupported JSON value type: " + value.getClass());
            }
        }

        private static void writeObject(StringBuilder sb, Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        }

        private static void writeArray(StringBuilder sb, List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, item);
            }
            sb.append(']');
        }

        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            sb.append('"');
        }

        /** A tiny recursive-descent parser for the same value subset {@link #writeValue} emits. */
        static final class Parser {
            private final String s;
            private int pos;

            Parser(String s) {
                this.s = s;
            }

            Object parse() {
                Object value = parseValue();
                skipWhitespace();
                if (pos != s.length()) {
                    throw new IllegalArgumentException("Trailing content in JSON at offset " + pos);
                }
                return value;
            }

            private Object parseValue() {
                skipWhitespace();
                char c = peek();
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't', 'f' -> parseBoolean();
                    case 'n' -> parseNull();
                    default -> parseNumber();
                };
            }

            private Map<String, Object> parseObject() {
                expect('{');
                Map<String, Object> result = new LinkedHashMap<>();
                skipWhitespace();
                if (peek() == '}') {
                    pos++;
                    return result;
                }
                while (true) {
                    skipWhitespace();
                    String key = parseString();
                    skipWhitespace();
                    expect(':');
                    Object value = parseValue();
                    result.put(key, value);
                    skipWhitespace();
                    char next = peek();
                    if (next == ',') {
                        pos++;
                    } else if (next == '}') {
                        pos++;
                        break;
                    } else {
                        throw new IllegalArgumentException("Expected ',' or '}' at offset " + pos);
                    }
                }
                return result;
            }

            private List<Object> parseArray() {
                expect('[');
                List<Object> result = new ArrayList<>();
                skipWhitespace();
                if (peek() == ']') {
                    pos++;
                    return result;
                }
                while (true) {
                    result.add(parseValue());
                    skipWhitespace();
                    char next = peek();
                    if (next == ',') {
                        pos++;
                    } else if (next == ']') {
                        pos++;
                        break;
                    } else {
                        throw new IllegalArgumentException("Expected ',' or ']' at offset " + pos);
                    }
                }
                return result;
            }

            private String parseString() {
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (true) {
                    char c = next();
                    if (c == '"') {
                        break;
                    }
                    if (c == '\\') {
                        char escaped = next();
                        switch (escaped) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                String hex = s.substring(pos, pos + 4);
                                pos += 4;
                                sb.append((char) Integer.parseInt(hex, 16));
                            }
                            default ->
                                    throw new IllegalArgumentException(
                                            "Unknown escape sequence '\\" + escaped + "' at offset " + pos);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return sb.toString();
            }

            private Boolean parseBoolean() {
                if (s.startsWith("true", pos)) {
                    pos += 4;
                    return Boolean.TRUE;
                }
                if (s.startsWith("false", pos)) {
                    pos += 5;
                    return Boolean.FALSE;
                }
                throw new IllegalArgumentException("Invalid literal at offset " + pos);
            }

            private Object parseNull() {
                if (s.startsWith("null", pos)) {
                    pos += 4;
                    return null;
                }
                throw new IllegalArgumentException("Invalid literal at offset " + pos);
            }

            private Object parseNumber() {
                int start = pos;
                if (peek() == '-') {
                    pos++;
                }
                boolean isFloatingPoint = false;
                while (pos < s.length() && isNumberChar(s.charAt(pos))) {
                    char c = s.charAt(pos);
                    if (c == '.' || c == 'e' || c == 'E') {
                        isFloatingPoint = true;
                    }
                    pos++;
                }
                if (pos == start) {
                    throw new IllegalArgumentException("Invalid character at offset " + pos);
                }
                String token = s.substring(start, pos);
                return isFloatingPoint ? (Object) Double.parseDouble(token) : (Object) Long.parseLong(token);
            }

            private static boolean isNumberChar(char c) {
                return Character.isDigit(c) || c == '.' || c == '-' || c == '+' || c == 'e' || c == 'E';
            }

            private void skipWhitespace() {
                while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                    pos++;
                }
            }

            private char peek() {
                if (pos >= s.length()) {
                    throw new IllegalArgumentException("Unexpected end of JSON input");
                }
                return s.charAt(pos);
            }

            private char next() {
                char c = peek();
                pos++;
                return c;
            }

            private void expect(char expected) {
                char actual = next();
                if (actual != expected) {
                    throw new IllegalArgumentException(
                            "Expected '" + expected + "' but found '" + actual + "' at offset " + (pos - 1));
                }
            }
        }
    }
}
