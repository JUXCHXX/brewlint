package io.github.brewlint.core.model;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Outcome of analysing a set of source files.
 *
 * <p>Note that {@code filesScanned} and {@code filesWithParseErrors} overlap by design. JavaParser
 * recovers from syntax errors and still returns a usable AST, so a broken file is still analysed
 * for whatever did parse, and is also counted as having errors. A file is only absent from
 * {@code filesScanned} if it could not be read or produced no AST at all.
 *
 * @param findings       every finding, already sorted by descending severity
 * @param filesScanned   number of Java files that were parsed and analysed
 * @param filesWithParseErrors number of files the parser reported at least one problem in
 * @param duration       wall-clock time spent analysing
 * @param toolVersion    version of Brewlint that produced this result
 */
public record AnalysisResult(
        List<Finding> findings,
        int filesScanned,
        int filesWithParseErrors,
        Duration duration,
        String toolVersion) {

    public AnalysisResult {
        Objects.requireNonNull(findings, "findings");
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(toolVersion, "toolVersion");
    }

    public Map<Severity, Long> countBySeverity() {
        return findings.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        Finding::severity,
                        java.util.stream.Collectors.counting()));
    }

    public long count(Severity severity) {
        return findings.stream().filter(finding -> finding.severity() == severity).count();
    }

    public long countAtLeast(Severity threshold) {
        return findings.stream().filter(finding -> finding.severity().atLeast(threshold)).count();
    }
}
