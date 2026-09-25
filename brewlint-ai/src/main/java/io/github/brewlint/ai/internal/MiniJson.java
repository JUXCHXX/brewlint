package io.github.brewlint.ai.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for model output.
 *
 * <p>Brewlint already has a writer in {@code brewlint-report} and chose not to add Jackson to a
 * two-megabyte binary. The same reasoning applies here with more force: this has to cope with
 * whatever a language model emits, which is a job no strict parser should be trusted with.
 *
 * <p>It handles the things models actually do: wrap JSON in a code fence, prefix it with a sentence
 * of prose, and occasionally emit a trailing comma. A strict parser rejects all three, and the
 * failure mode would be silently losing the whole review.
 */
public final class MiniJson {

    /** Raised for input that contains no usable JSON. Callers drop the result rather than guess. */
    public static final class MalformedException extends RuntimeException {
        public MalformedException(String message) {
            super(message);
        }
    }

    /** The text being parsed. A reader is not thread-safe, and one is created per parse. */
    private final String source;
    private int position;

    private MiniJson(String source) {
        this.source = source;
    }

    /**
     * Extracts and parses the first JSON object in {@code text}.
     *
     * <p>Handles a ```json fence and leading prose, both of which models produce routinely.
     */
    public static Object parseLoose(String text) {
        if (text == null || text.isBlank()) {
            throw new MalformedException("empty response");
        }
        String candidate = text.trim();

        int fenceStart = candidate.indexOf("```");
        if (fenceStart >= 0) {
            int afterFence = candidate.indexOf('\n', fenceStart);
            if (afterFence > 0) {
                int fenceEnd = candidate.indexOf("```", afterFence);
                candidate = fenceEnd > afterFence
                        ? candidate.substring(afterFence + 1, fenceEnd)
                        : candidate.substring(afterFence + 1);
            }
        }

        int objectStart = candidate.indexOf('{');
        if (objectStart < 0) {
            throw new MalformedException("no JSON object in response");
        }
        String balanced = balancedFrom(candidate, objectStart);
        return new MiniJson(balanced).parseValue();
    }

    /** Returns the substring starting at {@code start} and ending at its matching brace. */
    private static String balancedFrom(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }
            switch (character) {
                case '"' -> inString = true;
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth == 0) {
                        return text.substring(start, index + 1);
                    }
                }
                default -> {
                    // Not a structural character.
                }
            }
        }
        throw new MalformedException("unbalanced JSON in response");
    }

    private Object parseValue() {
        skipWhitespace();
        if (position >= source.length()) {
            throw new MalformedException("unexpected end of input");
        }
        char character = source.charAt(position);
        return switch (character) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> parseString();
            case 't' -> parseLiteral("true", Boolean.TRUE);
            case 'f' -> parseLiteral("false", Boolean.FALSE);
            case 'n' -> parseLiteral("null", null);
            default -> parseNumber();
        };
    }

    private Map<String, Object> parseObject() {
        Map<String, Object> result = new LinkedHashMap<>();
        expect('{');
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            String key = parseString();
            skipWhitespace();
            expect(':');
            result.put(key, parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                position++;
                skipWhitespace();
                // A trailing comma before the closing brace is a model habit, not an error.
                if (peek() == '}') {
                    position++;
                    return result;
                }
                continue;
            }
            if (next == '}') {
                position++;
                return result;
            }
            throw new MalformedException("expected , or } at " + position);
        }
    }

    private List<Object> parseArray() {
        List<Object> result = new ArrayList<>();
        expect('[');
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return result;
        }
        while (true) {
            skipWhitespace();
            result.add(parseValue());
            skipWhitespace();
            char next = peek();
            if (next == ',') {
                position++;
                skipWhitespace();
                if (peek() == ']') {
                    position++;
                    return result;
                }
                continue;
            }
            if (next == ']') {
                position++;
                return result;
            }
            throw new MalformedException("expected , or ] at " + position);
        }
    }

    private String parseString() {
        expect('"');
        StringBuilder value = new StringBuilder();
        while (position < source.length()) {
            char character = source.charAt(position++);
            if (character == '"') {
                return value.toString();
            }
            if (character != '\\') {
                value.append(character);
                continue;
            }
            if (position >= source.length()) {
                break;
            }
            char escaped = source.charAt(position++);
            switch (escaped) {
                case 'n' -> value.append('\n');
                case 't' -> value.append('\t');
                case 'r' -> value.append('\r');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'u' -> {
                    if (position + 4 > source.length()) {
                        throw new MalformedException("truncated unicode escape");
                    }
                    value.append((char) Integer.parseInt(source.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> value.append(escaped);
            }
        }
        throw new MalformedException("unterminated string");
    }

    private Object parseLiteral(String literal, Object value) {
        if (!source.startsWith(literal, position)) {
            throw new MalformedException("expected " + literal + " at " + position);
        }
        position += literal.length();
        return value;
    }

    private Object parseNumber() {
        int start = position;
        while (position < source.length() && "+-0123456789.eE".indexOf(source.charAt(position)) >= 0) {
            position++;
        }
        String literal = source.substring(start, position);
        try {
            if (literal.contains(".") || literal.contains("e") || literal.contains("E")) {
                return Double.parseDouble(literal);
            }
            return Long.parseLong(literal);
        } catch (NumberFormatException exception) {
            throw new MalformedException("not a number: " + literal);
        }
    }

    private void skipWhitespace() {
        while (position < source.length() && Character.isWhitespace(source.charAt(position))) {
            position++;
        }
    }

    private char peek() {
        if (position >= source.length()) {
            throw new MalformedException("unexpected end of input");
        }
        return source.charAt(position);
    }

    private void expect(char expected) {
        if (peek() != expected) {
            throw new MalformedException("expected " + expected + " at " + position);
        }
        position++;
    }

    // Typed accessors. These answer rather than throw, because a model that returned the wrong type
    // for one field should cost that field, not the whole response.

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    public static List<Object> asArray(Object value) {
        return value instanceof List<?> list ? List.copyOf(list) : List.of();
    }

    public static String asString(Object value, String fallback) {
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    /** Never throws: a line the model wrote as "line 42" or omitted entirely becomes {@code fallback}. */
    public static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.replaceAll("[^0-9-]", "").trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
