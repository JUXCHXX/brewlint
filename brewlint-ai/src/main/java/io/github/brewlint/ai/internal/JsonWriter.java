package io.github.brewlint.ai.internal;

import java.util.Collection;
import java.util.Map;

/**
 * Writes the request body as JSON.
 *
 * <p>The counterpart to {@link MiniJson}, and deliberately the same amount of code rather than a
 * dependency. A request to a paid API has to be right, and a serialiser that escapes strings
 * properly is the one thing not to improvise.
 */
public final class JsonWriter {

    private JsonWriter() {
    }

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        writeValue(value, out);
        return out.toString();
    }

    private static void writeValue(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String text) {
            writeString(text, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(map, out);
        } else if (value instanceof Collection<?> collection) {
            writeArray(collection, out);
        } else {
            // Anything unrecognised goes out as a string. A request that is slightly wrong is
            // recoverable; one that throws while being built is not.
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeObject(Map<?, ?> map, StringBuilder out) {
        out.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeString(String.valueOf(entry.getKey()), out);
            out.append(':');
            writeValue(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeArray(Collection<?> collection, StringBuilder out) {
        out.append('[');
        boolean first = true;
        for (Object element : collection) {
            if (!first) {
                out.append(',');
            }
            first = false;
            writeValue(element, out);
        }
        out.append(']');
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20) {
                        out.append(String.format("\\u%04x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        out.append('"');
    }
}
