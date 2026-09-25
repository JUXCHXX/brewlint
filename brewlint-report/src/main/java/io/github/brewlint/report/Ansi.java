package io.github.brewlint.report;

import io.github.brewlint.core.model.Severity;

/**
 * ANSI colouring, disabled wholesale when the output is not a terminal.
 *
 * <p>Every styling method is a no-op when colour is off, so callers can decorate freely without
 * threading a boolean through each call site and without the risk of a missing check leaking escape
 * codes into a redirected file or a CI log.
 *
 * <p>Whether colour is appropriate is decided by the caller. {@code AnsiSupport} in the CLI module
 * answers that: an explicit {@code --color}/{@code --no-color} first, then the {@code NO_COLOR}
 * convention, then whether stdout is a terminal.
 *
 * <p>Instances are immutable and safe to share.
 */
final class Ansi {

    private static final String RESET = "[0m";
    private static final String BOLD = "[1m";
    private static final String DIM = "[2m";

    private final boolean enabled;

    Ansi(boolean enabled) {
        this.enabled = enabled;
    }

    static Ansi disabled() {
        return new Ansi(false);
    }

    boolean enabled() {
        return enabled;
    }

    String bold(String text) {
        return decorate(BOLD, text);
    }

    String dim(String text) {
        return decorate(DIM, text);
    }

    String severity(Severity severity, String text) {
        return decorate(switch (severity) {
            case ERROR -> "[31m";
            case WARNING -> "[33m";
            case INFO -> "[36m";
        }, text);
    }

    String accent(String text) {
        return decorate("[35m", text);
    }

    String success(String text) {
        return decorate("[32m", text);
    }

    private String decorate(String code, String text) {
        return enabled ? code + text + RESET : text;
    }
}
