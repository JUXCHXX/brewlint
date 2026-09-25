package io.github.brewlint.core.model;

import java.util.Comparator;
import java.util.Objects;

/**
 * A single problem found in the analysed source.
 *
 * <p>This is the contract every other piece of Brewlint consumes: the terminal renderer, the JSON
 * output consumed by the VS Code extension, the PDF report and the GitHub Action all read exactly
 * this shape. Keep it small, immutable and free of presentation concerns.
 *
 * @param ruleId     stable rule identifier, e.g. {@code TX001}. Never localised, never renamed.
 * @param category   rule family, e.g. {@code transactional}
 * @param severity   effective severity after configuration overrides
 * @param filePath   project-relative path of the offending file
 * @param line       1-based line number
 * @param column     1-based column number
 * @param endLine    1-based line where the offending construct ends
 * @param message    what is wrong, in one sentence
 * @param suggestion how to fix it, in one sentence
 */
public record Finding(
        String ruleId,
        String category,
        Severity severity,
        String filePath,
        int line,
        int column,
        int endLine,
        String message,
        String suggestion) implements Comparable<Finding> {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(filePath, "filePath");
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(suggestion, "suggestion");
        if (line < 1) {
            throw new IllegalArgumentException("line must be 1-based, got " + line);
        }
        if (column < 1) {
            throw new IllegalArgumentException("column must be 1-based, got " + column);
        }
    }

    /** Orders findings by descending severity, then by location, then by rule id. */
    private static final Comparator<Finding> ORDER = Comparator
            .comparingInt((Finding finding) -> -finding.severity().weight())
            .thenComparing(Finding::filePath)
            .thenComparingInt(Finding::line)
            .thenComparingInt(Finding::column)
            .thenComparing(Finding::ruleId);

    @Override
    public int compareTo(Finding other) {
        return ORDER.compare(this, other);
    }
}
