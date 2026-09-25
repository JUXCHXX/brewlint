package io.github.brewlint.report;

import io.github.brewlint.core.Brewlint;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Human-readable report for the terminal.
 *
 * <p>Grouped by file, because that is how a developer reads a list of problems, and it puts the
 * first thing worth acting on at the top: the file and line, not the rule name.
 */
public final class TerminalReportRenderer implements ReportRenderer {

    /** Width of the severity column, sized to the longest name so columns line up. */
    private static final int SEVERITY_WIDTH = 7;
    private static final int POSITION_WIDTH = 8;
    private static final int RULE_BODY_INDENT = 6;

    @Override
    public String format() {
        return "terminal";
    }

    @Override
    public void render(AnalysisResult result, ReportOptions options, Appendable target) throws IOException {
        Ansi ansi = new Ansi(options.color());
        StringBuilder out = new StringBuilder();

        out.append('\n').append(ansi.bold(Brewlint.NAME + " " + result.toolVersion())).append('\n');

        if (result.findings().isEmpty()) {
            renderClean(result, ansi, out);
        } else {
            renderFindings(result, options, ansi, out);
        }

        renderSummary(result, options, ansi, out);

        target.append(out);
    }

    private void renderClean(AnalysisResult result, Ansi ansi, StringBuilder out) {
        out.append('\n').append("  ").append(ansi.success("No findings."))
                .append(" Scanned ").append(result.filesScanned())
                .append(result.filesScanned() == 1 ? " file." : " files.")
                .append('\n');
    }

    private void renderFindings(AnalysisResult result, ReportOptions options, Ansi ansi, StringBuilder out) {
        Map<String, List<Finding>> byFile = new LinkedHashMap<>();
        for (Finding finding : result.findings()) {
            byFile.computeIfAbsent(finding.filePath(), key -> new ArrayList<>()).add(finding);
        }

        int printed = 0;
        for (Map.Entry<String, List<Finding>> entry : byFile.entrySet()) {
            out.append('\n').append("  ").append(ansi.bold(entry.getKey())).append('\n');

            for (Finding finding : entry.getValue()) {
                if (options.maxFindings() != null && printed >= options.maxFindings()) {
                    out.append("    ").append(ansi.dim("... output truncated at "
                            + options.maxFindings() + " findings; the summary below counts them all"))
                            .append('\n');
                    return;
                }
                printed++;
                renderFinding(finding, options, ansi, out);
            }
        }
    }

    private void renderFinding(Finding finding, ReportOptions options, Ansi ansi, StringBuilder out) {
        out.append('\n')
                .append("    ")
                .append(ansi.severity(finding.severity(), pad(finding.severity().name(), SEVERITY_WIDTH)))
                .append("  ")
                .append(ansi.dim(pad(finding.line() + ":" + finding.column(), POSITION_WIDTH)))
                .append("  ")
                .append(ansi.bold(finding.ruleId()))
                .append('\n');

        appendWrapped(out, finding.message(), options.width());
        out.append('\n');
        appendWrapped(out, ansi.accent("-> ") + finding.suggestion(), options.width());
    }

    private void renderSummary(AnalysisResult result, ReportOptions options, Ansi ansi, StringBuilder out) {
        // filesScanned counts every file analysed, which is not the same as the files that produced
        // findings. Conflating the two makes the summary claim findings live in files they do not.
        long filesWithFindings = result.findings().stream()
                .map(Finding::filePath)
                .distinct()
                .count();

        out.append('\n')
                .append("  ").append("-".repeat(Math.min(options.width(), 78))).append('\n')
                .append("  ")
                .append(ansi.bold(result.findings().size() == 1
                        ? "1 finding"
                        : result.findings().size() + " findings"))
                .append(" in ")
                .append(filesWithFindings == 1 ? "1 file" : filesWithFindings + " files")
                .append("  ·  ")
                .append(ansi.dim(result.filesScanned() == 1
                        ? "1 file scanned"
                        : result.filesScanned() + " files scanned"))
                .append("  ·  ")
                .append(ansi.severity(Severity.ERROR, result.count(Severity.ERROR) + " errors"))
                .append(", ")
                .append(ansi.severity(Severity.WARNING, result.count(Severity.WARNING) + " warnings"))
                .append(", ")
                .append(ansi.severity(Severity.INFO, result.count(Severity.INFO) + " info"))
                .append("  ·  ")
                .append(ansi.dim(formatMillis(result.duration().toMillis())))
                .append('\n');

        if (result.filesWithParseErrors() > 0) {
            out.append("  ")
                    .append(ansi.dim(result.filesWithParseErrors()
                            + " file(s) had parse problems; findings in them may be incomplete"))
                    .append('\n');
        }
        out.append('\n');
    }

    private static void appendWrapped(StringBuilder out, String text, int width) {
        for (String line : wrap(text, width - RULE_BODY_INDENT)) {
            out.append(" ".repeat(RULE_BODY_INDENT)).append(line).append('\n');
        }
    }

    /**
     * Greedy word wrap. Words longer than the line are left alone rather than split, because a
     * mangled identifier is worse than an overlong line.
     */
    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (current.isEmpty()) {
                current.append(word);
            } else if (current.length() + 1 + word.length() <= width) {
                current.append(' ').append(word);
            } else {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static String pad(String value, int width) {
        return value.length() >= width ? value : value + " ".repeat(width - value.length());
    }

    private static String formatMillis(long millis) {
        return millis < 1000
                ? millis + "ms"
                : String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
    }
}
