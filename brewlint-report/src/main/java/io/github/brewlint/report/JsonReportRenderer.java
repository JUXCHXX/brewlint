package io.github.brewlint.report;

import io.github.brewlint.core.Brewlint;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;

import java.io.IOException;
import java.util.List;

/**
 * Machine-readable report.
 *
 * <p>This is the contract the VS Code extension and the GitHub Action will consume, which makes it
 * the most consequential format in the project: a change here is a breaking change for every client
 * written against it. Hito 6 reads it to build editor diagnostics, and Hito 7 reads it to write a
 * pull request comment.
 *
 * <p>Design rules for a format other programs depend on:
 * <ul>
 *   <li>Every key is always present, even when its value is null. A consumer never has to tell
 *       "absent" from "empty".</li>
 *   <li>Severities are their enum names, not a number. A number would need a legend.</li>
 *   <li>Positions are 1-based line and column, matching what every editor shows.</li>
 *   <li>Paths are relative with forward slashes, so the same commit produces the same bytes on
 *       every platform.</li>
 *   <li>Output is pretty-printed and ends with a newline, so it diffs cleanly when a run is
 *       committed as an artefact.</li>
 * </ul>
 */
public final class JsonReportRenderer implements ReportRenderer {

    private static final String INDENT = "  ";

    @Override
    public String format() {
        return "json";
    }

    @Override
    public void render(AnalysisResult result, ReportOptions options, Appendable target)
            throws IOException {
        target.append(toJson(result));
    }

    /** Renders to a string. Convenient for tests and for the client libraries. */
    public String toJson(AnalysisResult result) {
        StringBuilder out = new StringBuilder();
        out.append("{\n");

        out.append(INDENT).append(Json.field("schemaVersion", 1)).append(",\n");
        out.append(INDENT).append(Json.field("tool", Brewlint.NAME)).append(",\n");
        out.append(INDENT).append(Json.field("toolVersion", result.toolVersion())).append(",\n");
        out.append(INDENT).append(Json.field("filesScanned", result.filesScanned())).append(",\n");
        out.append(INDENT).append(Json.field("filesWithParseErrors", result.filesWithParseErrors()))
                .append(",\n");
        out.append(INDENT).append(Json.field("durationMillis", result.duration().toMillis()))
                .append(",\n");
        out.append(INDENT).append(Json.rawField("counts", counts(result))).append(",\n");

        out.append(INDENT).append(Json.rawField("findings", findings(result.findings())));
        out.append("\n}\n");
        return out.toString();
    }

    private String counts(AnalysisResult result) {
        return "{\n"
                + INDENT.repeat(2) + Json.field("error", result.count(Severity.ERROR)) + Json.separator() + "\n"
                + INDENT.repeat(2) + Json.field("warning", result.count(Severity.WARNING)) + Json.separator() + "\n"
                + INDENT.repeat(2) + Json.field("info", result.count(Severity.INFO)) + "\n"
                + INDENT + "}";
    }

    private String findings(List<Finding> findings) {
        if (findings.isEmpty()) {
            return "[]";
        }
        StringBuilder out = new StringBuilder("[\n");
        for (int index = 0; index < findings.size(); index++) {
            out.append(INDENT.repeat(2)).append(finding(findings.get(index)));
            if (index < findings.size() - 1) {
                out.append(Json.separator());
            }
            out.append('\n');
        }
        out.append(INDENT).append(']');
        return out.toString();
    }

    private String finding(Finding finding) {
        return "{" + String.join(Json.separator(),
                Json.field("ruleId", finding.ruleId()),
                Json.field("category", finding.category()),
                Json.field("severity", finding.severity().name()),
                Json.field("file", finding.filePath()),
                Json.field("line", finding.line()),
                Json.field("column", finding.column()),
                Json.field("endLine", finding.endLine()),
                Json.field("message", finding.message()),
                Json.field("suggestion", finding.suggestion())) + "}";
    }
}
