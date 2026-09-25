package io.github.brewlint.report;

/**
 * The smallest JSON writer that is still correct.
 *
 * <p>Brewlint's JSON output is a fixed, shallow shape, and adding Jackson to the shaded jar would
 * add roughly two megabytes and a stream of CVE notices to a tool whose whole selling point is a
 * two-megabyte jar. About a hundred lines is a better trade than that.
 *
 * <p>The part that actually matters is {@link #escape(String)}. A finding message quotes the user's
 * own code, so it will contain quotes and backslashes, and Java identifiers can contain characters
 * that would break the document. Escaping is where a hand-rolled writer usually goes wrong, so it
 * is tested against the full set of cases JSON requires.
 */
final class Json {

    private Json() {
    }

    /**
     * Writes {@code "name": "value"}.
     *
     * @param name  member name, already known to be a plain identifier
     * @param value member value, escaped
     */
    static String field(String name, String value) {
        return quote(name) + ":" + quote(value);
    }

    /**
     * Writes {@code "name": <value>} where the value is already serialised JSON, emitted verbatim.
     *
     * <p>For nested objects and arrays. Using {@link #field(String, String)} for those would quote
     * them, which still produces valid JSON that every parser accepts and every consumer
     * misreads.
     */
    static String rawField(String name, String rawValue) {
        return quote(name) + ":" + rawValue;
    }

    static String field(String name, int value) {
        return quote(name) + ":" + value;
    }

    static String field(String name, long value) {
        return quote(name) + ":" + value;
    }

    static String separator() {
        return ",";
    }

    /**
     * A JSON string literal, fully escaped.
     *
     * <p>Escapes the two mandatory characters, the five short control escapes, and any remaining
     * character below 0x20 as {@code \\uXXXX}, which is what the specification requires. Everything
     * else, including non-ASCII, is emitted as-is: the output is UTF-8 and escaping it would make
     * a message with an accent or an arrow harder to read in a log.
     */
    static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
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
        return out.toString();
    }
}
