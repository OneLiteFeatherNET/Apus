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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal recursive-descent JSON reader covering exactly what parsing a Pterodactyl panel
 * response needs: objects, arrays, strings, numbers, booleans and null.
 *
 * <p>This exists only because the ingest module cannot take on a general-purpose JSON library
 * dependency from within this task's file scope (build files are out of bounds -- see the task
 * report). It is intentionally not a general-purpose JSON library.
 */
final class MinimalJson {

    private MinimalJson() {}

    static Object parse(String json) {
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("trailing content after JSON value at offset " + parser.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("expected a JSON object but found " + describe(value));
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("expected a JSON array but found " + describe(value));
        }
        return (List<Object>) list;
    }

    static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalArgumentException("expected a JSON number but found " + describe(value));
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        boolean atEnd() {
            return pos >= src.length();
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            Object value =
                    switch (c) {
                        case '{' -> parseObject();
                        case '[' -> parseArray();
                        case '"' -> parseString();
                        case 't', 'f' -> parseBoolean();
                        case 'n' -> parseNull();
                        default -> parseNumber();
                    };
            return value;
        }

        Map<String, Object> parseObject() {
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
                pos++;
                if (next == '}') {
                    return result;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("expected ',' or '}' at offset " + (pos - 1));
                }
            }
        }

        List<Object> parseArray() {
            expect('[');
            List<Object> result = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                Object value = parseValue();
                result.add(value);
                skipWhitespace();
                char next = peek();
                pos++;
                if (next == ']') {
                    return result;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("expected ',' or ']' at offset " + (pos - 1));
                }
                skipWhitespace();
            }
        }

        String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (true) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    char escaped = src.charAt(pos++);
                    switch (escaped) {
                        case '"' -> builder.append('"');
                        case '\\' -> builder.append('\\');
                        case '/' -> builder.append('/');
                        case 'b' -> builder.append('\b');
                        case 'f' -> builder.append('\f');
                        case 'n' -> builder.append('\n');
                        case 'r' -> builder.append('\r');
                        case 't' -> builder.append('\t');
                        case 'u' -> {
                            String hex = src.substring(pos, pos + 4);
                            builder.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                        }
                        default -> throw new IllegalArgumentException("invalid escape '\\" + escaped + "' at offset " + pos);
                    }
                } else {
                    builder.append(c);
                }
            }
        }

        Boolean parseBoolean() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new IllegalArgumentException("invalid literal at offset " + pos);
        }

        Object parseNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new IllegalArgumentException("invalid literal at offset " + pos);
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean isFloatingPoint = false;
            while (!atEnd()) {
                char c = src.charAt(pos);
                if (Character.isDigit(c)) {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloatingPoint = isFloatingPoint || c == '.' || c == 'e' || c == 'E';
                    pos++;
                } else {
                    break;
                }
            }
            String literal = src.substring(start, pos);
            if (literal.isEmpty() || "-".equals(literal)) {
                throw new IllegalArgumentException("invalid number at offset " + start);
            }
            return isFloatingPoint ? Double.parseDouble(literal) : Long.parseLong(literal);
        }

        void expect(char c) {
            if (atEnd() || src.charAt(pos) != c) {
                throw new IllegalArgumentException("expected '" + c + "' at offset " + pos);
            }
            pos++;
        }

        char peek() {
            if (atEnd()) {
                throw new IllegalArgumentException("unexpected end of JSON input");
            }
            return src.charAt(pos);
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(src.charAt(pos))) {
                pos++;
            }
        }
    }
}
