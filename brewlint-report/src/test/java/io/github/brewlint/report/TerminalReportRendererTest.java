package io.github.brewlint.report;

import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TerminalReportRenderer")
class TerminalReportRendererTest {

    private static final char ESCAPE = '\u001B';

    private final TerminalReportRenderer renderer = new TerminalReportRenderer();

    private String render(AnalysisResult result, ReportOptions options) throws IOException {
        StringBuilder out = new StringBuilder();
        renderer.render(result, options, out);
        return out.toString();
    }

    @Test
    @DisplayName("identifies itself as terminal")
    void format() {
        assertThat(renderer.format()).isEqualTo("terminal");
    }

    @Test
    @DisplayName("says so plainly when there is nothing to report")
    void cleanReport() throws IOException {
        String output = render(result(List.of(), 12), ReportOptions.defaults());

        assertThat(output).contains("No findings").contains("12 files");
    }

    @Test
    @DisplayName("groups findings under their file, keeping the order the engine produced")
    void groupsByFile() throws IOException {
        String output = render(
                result(List.of(
                        finding("A.java", 3, Severity.ERROR, "AOP001"),
                        finding("B.java", 9, Severity.WARNING, "RES001"),
                        finding("B.java", 4, Severity.WARNING, "RES001")), 3),
                ReportOptions.defaults());

        assertThat(output).contains("A.java").contains("B.java").contains("AOP001").contains("RES001");
        // Each file gets one header, and the two B.java findings sit under it.
        assertThat(output.indexOf("A.java")).isLessThan(output.indexOf("B.java"));
        assertThat(output.substring(output.indexOf("B.java"))).contains("RES001");
    }

    @Test
    @DisplayName("counts files with findings separately from files scanned")
    void doesNotConflateScannedWithAffected() throws IOException {
        // Two findings in one file, but twenty files analysed. Reporting "2 findings in 20 files"
        // would be a lie about where the findings are.
        String output = render(result(List.of(
                finding("A.java", 3, Severity.ERROR, "AOP001"),
                finding("A.java", 8, Severity.ERROR, "RES001")), 20),
                ReportOptions.defaults());

        assertThat(output).contains("2 findings in 1 file");
        assertThat(output).contains("20 files scanned");
    }

    @Test
    @DisplayName("emits no escape sequences when colour is off")
    void noEscapeSequencesWhenColourDisabled() throws IOException {
        String output = render(
                result(List.of(finding("A.java", 3, Severity.ERROR, "AOP001")), 1),
                ReportOptions.defaults().withColor(false));

        assertThat(output).doesNotContain(String.valueOf(ESCAPE));
        assertThat(output).contains("AOP001");
    }

    @Test
    @DisplayName("emits escape sequences when colour is on")
    void escapeSequencesWhenColourEnabled() throws IOException {
        String output = render(
                result(List.of(finding("A.java", 3, Severity.ERROR, "AOP001")), 1),
                ReportOptions.defaults().withColor(true));

        assertThat(output).contains(String.valueOf(ESCAPE) + "[");
    }

    @Test
    @DisplayName("counts each severity separately")
    void countsSeverities() throws IOException {
        String output = render(result(List.of(
                finding("A.java", 3, Severity.ERROR, "AOP001"),
                finding("A.java", 4, Severity.WARNING, "RES001"),
                finding("A.java", 5, Severity.INFO, "RES001")), 1),
                ReportOptions.defaults());

        assertThat(output).contains("1 errors").contains("1 warnings").contains("1 info");
    }

    @Test
    @DisplayName("truncates long output but still counts everything in the summary")
    void truncatesWithoutLying() throws IOException {
        List<Finding> many = new java.util.ArrayList<>();
        for (int index = 1; index <= 50; index++) {
            many.add(finding("A.java", index, Severity.ERROR, "AOP001"));
        }

        String output = render(result(many, 1), ReportOptions.defaults().withMaxFindings(3));

        assertThat(output).contains("truncated at 3 findings");
        assertThat(output).contains("50 findings");
    }

    @Test
    @DisplayName("warns when a file had parse problems, since its findings may be incomplete")
    void flagsParseProblems() throws IOException {
        AnalysisResult withErrors = new AnalysisResult(
                List.of(finding("A.java", 3, Severity.ERROR, "AOP001")), 1, 1, Duration.ofMillis(12), "test");

        assertThat(render(withErrors, ReportOptions.defaults()))
                .contains("1 file(s) had parse problems");
    }

    @Test
    @DisplayName("wraps long messages instead of running past the terminal")
    void wrapsLongText() throws IOException {
        Finding verbose = new Finding("AOP001", "spring-aop", Severity.ERROR, "A.java", 3, 5, 3,
                "A very long message that keeps going well past the configured width so that the "
                        + "renderer has to break it across several lines to stay readable",
                "A very long suggestion that also keeps going past the width of the terminal so "
                        + "that it must be wrapped as well to avoid a line nobody can read");

        String output = render(result(List.of(verbose), 1), new ReportOptions(false, null, 80));

        // The guarantee covers the message and suggestion body, which is where a long identifier
        // can appear. A word longer than the line is deliberately left intact.
        List<String> bodyLines = output.lines()
                .filter(line -> line.startsWith("      "))
                .toList();

        assertThat(bodyLines).isNotEmpty();
        assertThat(bodyLines).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(80));
    }

    private static AnalysisResult result(List<Finding> findings, int filesScanned) {
        return new AnalysisResult(findings, filesScanned, 0, Duration.ofMillis(42), "1.2.3");
    }

    private static Finding finding(String file, int line, Severity severity, String ruleId) {
        return new Finding(ruleId, "test", severity, file, line, 5, line,
                "Something is wrong here.", "Do this instead.");
    }
}
