package io.github.brewlint.core.model;

import java.util.Locale;

/**
 * How badly a finding breaks the project.
 *
 * <p>The ordering is meaningful: {@code ERROR > WARNING > INFO}. Use {@link #atLeast(Severity)}
 * to compare against a configured threshold.
 */
public enum Severity {

    /** The code is broken or silently does nothing. Fix before shipping. */
    ERROR(3),

    /** Suspicious and a common source of future bugs, but not currently broken. */
    WARNING(2),

    /** Convention or style. Worth knowing, not worth blocking a build over. */
    INFO(1);

    private final int weight;

    Severity(int weight) {
        this.weight = weight;
    }

    public int weight() {
        return weight;
    }

    /** Returns true when this severity is at least as serious as {@code threshold}. */
    public boolean atLeast(Severity threshold) {
        return weight >= threshold.weight;
    }

    /**
     * Parses a severity from configuration, case-insensitively.
     *
     * @throws IllegalArgumentException if the value is not a known severity
     */
    public static Severity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Severity cannot be empty");
        }
        String normalised = raw.trim().toUpperCase(Locale.ROOT);
        for (Severity severity : values()) {
            if (severity.name().equals(normalised)) {
                return severity;
            }
        }
        throw new IllegalArgumentException(
                "Unknown severity '" + raw + "'. Expected one of: ERROR, WARNING, INFO");
    }
}
