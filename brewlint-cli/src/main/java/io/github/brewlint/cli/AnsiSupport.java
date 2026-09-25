package io.github.brewlint.cli;

import java.io.PrintStream;

/**
 * Decides whether the output may carry ANSI escape sequences.
 *
 * <p>Precedence, in order: an explicit {@code --color} or {@code --no-color}, then the
 * {@code NO_COLOR} convention, then whether stdout is a terminal.
 *
 * <p>Colour off when output is redirected is not a detail. A CI log full of {@code [31m} tokens is
 * unreadable, and a developer who pipes the report to a file or to {@code less} wants plain text.
 */
final class AnsiSupport {

    private AnsiSupport() {
    }

    static boolean isColorEnabled(boolean noColor, boolean forceColor) {
        if (noColor) {
            return false;
        }
        if (forceColor) {
            return true;
        }
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        return System.console() != null;
    }

    /** True when the given stream is attached to a terminal. */
    static boolean isTerminal(PrintStream stream) {
        return System.console() != null && stream == System.out;
    }
}
