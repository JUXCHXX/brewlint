package io.github.brewlint.core.engine;

/**
 * Glob matching for the {@code exclude} list, supporting {@code **}, {@code *} and {@code ?}.
 *
 * <p>Hand-rolled rather than pulled from a dependency: this is a few dozen lines, and a linter that
 * starts instantly is worth more than the convenience. The supported syntax is intentionally
 * narrower than a full glob library and is documented in the README.
 */
public final class GlobPattern {

    private final String glob;
    private final java.util.regex.Pattern pattern;

    private GlobPattern(String glob, java.util.regex.Pattern pattern) {
        this.glob = glob;
        this.pattern = pattern;
    }

    public static GlobPattern compile(String glob) {
        return new GlobPattern(glob, java.util.regex.Pattern.compile(toRegex(glob)));
    }

    public boolean matches(String path) {
        return pattern.matcher(path).matches();
    }

    @Override
    public String toString() {
        return glob;
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 16);
        int index = 0;
        while (index < glob.length()) {
            char current = glob.charAt(index);
            int consumed;
            switch (current) {
                case '*' -> {
                    if (glob.startsWith("**/", index)) {
                        // '**/' has to match zero directories too, so "a/**/b" also matches "a/b".
                        regex.append("(?:.*/)?");
                        consumed = 3;
                    } else if (glob.startsWith("**", index)) {
                        regex.append(".*");
                        consumed = 2;
                    } else {
                        regex.append("[^/]*");
                        consumed = 1;
                    }
                }
                case '?' -> {
                    regex.append("[^/]");
                    consumed = 1;
                }
                case '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' -> {
                    regex.append('\\').append(current);
                    consumed = 1;
                }
                default -> {
                    regex.append(current);
                    consumed = 1;
                }
            }
            // Every branch must consume at least one character, or this loop never ends.
            index += consumed;
        }
        return regex.toString();
    }
}
