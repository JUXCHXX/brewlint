package io.github.brewlint.report;

/**
 * Options that affect how a result is presented, as opposed to what it contains.
 *
 * @param color        emit ANSI escape sequences
 * @param maxFindings  stop printing individual findings after this many, or {@code null} for all.
 *                     A CI log with 4000 findings is unreadable; the summary always counts them all.
 * @param width        target line width for wrapped text
 */
public record ReportOptions(boolean color, Integer maxFindings, int width) {

    public static final int DEFAULT_WIDTH = 100;

    public static ReportOptions defaults() {
        return new ReportOptions(false, null, DEFAULT_WIDTH);
    }

    public ReportOptions {
        if (width < 40) {
            throw new IllegalArgumentException("width must be at least 40, got " + width);
        }
    }

    public ReportOptions withColor(boolean enabled) {
        return new ReportOptions(enabled, maxFindings, width);
    }

    public ReportOptions withMaxFindings(Integer limit) {
        return new ReportOptions(color, limit, width);
    }
}
