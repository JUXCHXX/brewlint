package io.github.brewlint.report;

import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JsonReportRenderer: the contract the VS Code extension and the Action will read")
class JsonReportRendererTest {

    private final JsonReportRenderer renderer = new JsonReportRenderer();

    private String render(AnalysisResult result) {
        return renderer.toJson(result);
    }

    @Test
    @DisplayName("identifies itself as json")
    void format() {
        assertThat(renderer.format()).isEqualTo("json");
    }

    @Test
    @DisplayName("every top-level key is present even with no findings")
    void keysAlwaysPresent() {
        String json = render(new AnalysisResult(List.of(), 0, 0, Duration.ZERO, "1.2.3"));

        assertThat(json)
                .contains("\"schemaVersion\":1")
                .contains("\"tool\":\"brewlint\"")
                .contains("\"toolVersion\":\"1.2.3\"")
                .contains("\"filesScanned\":0")
                .contains("\"filesWithParseErrors\":0")
                .contains("\"durationMillis\":0")
                .contains("\"counts\":{")
                .contains("\"findings\":[]");
    }

    @Test
    @DisplayName("nested objects and arrays are real JSON values, not quoted strings")
    void nestedValuesAreNotStrings() {
        // The failure mode this guards against produces valid JSON that every parser accepts and
        // every consumer misreads, because a string was written where an object belongs.
        String json = render(result(List.of(finding("A.java", 3)), 1));

        assertThat(json)
                .as("counts must be an object, not a quoted string")
                .doesNotContain("\"counts\":\"")
                .contains("\"counts\":{");
        assertThat(json)
                .as("findings must be an array, not a quoted string")
                .doesNotContain("\"findings\":\"");
        assertThat(json).contains("\"findings\":[");
    }

    @Test
    @DisplayName("a finding carries everything a client needs to draw a diagnostic")
    void findingShape() {
        String json = render(result(List.of(finding("A.java", 3)), 1));

        assertThat(json).contains("\"ruleId\":\"AOP001\"")
                .contains("\"category\":\"spring-aop\"")
                .contains("\"severity\":\"ERROR\"")
                .contains("\"file\":\"A.java\"")
                .contains("\"line\":3")
                .contains("\"column\":9")
                .contains("\"endLine\":3")
                .contains("\"message\":\"Something is wrong here.\"")
                .contains("\"suggestion\":\"Do this instead.\"");
    }

    @Test
    @DisplayName("severity is the enum name, not a number")
    void severityIsAName() {
        String json = render(result(List.of(
                finding("A.java", 1, Severity.ERROR),
                finding("A.java", 2, Severity.WARNING),
                finding("A.java", 3, Severity.INFO)), 1));

        assertThat(json)
                .contains("\"severity\":\"ERROR\"")
                .contains("\"severity\":\"WARNING\"")
                .contains("\"severity\":\"INFO\"");
    }

    @Test
    @DisplayName("counts match the findings")
    void countsAreAccurate() {
        String json = render(result(List.of(
                finding("A.java", 1, Severity.ERROR),
                finding("A.java", 2, Severity.ERROR),
                finding("A.java", 3, Severity.WARNING)), 1));

        assertThat(json).contains("\"error\":2").contains("\"warning\":1").contains("\"info\":0");
    }

    @Test
    @DisplayName("several findings are separated by commas, not run together")
    void commaBetweenFindings() {
        String json = render(result(List.of(
                finding("A.java", 1),
                finding("A.java", 2),
                finding("A.java", 3)), 1));

        assertThat(json).contains("},");
        assertThat(json.split("\"ruleId\"", -1)).hasSize(4);
    }

    @Test
    @DisplayName("ends with a newline so the output diffs cleanly as a CI artefact")
    void endsWithNewline() {
        assertThat(render(result(List.of(), 0))).endsWith("}\n");
    }

    @Test
    @DisplayName("writes to any Appendable, so it works for a file as well as a stream")
    void writesToAppendable() throws IOException {
        StringBuilder target = new StringBuilder();
        renderer.render(result(List.of(finding("A.java", 1)), 1), ReportOptions.defaults(), target);

        assertThat(target.toString()).contains("\"findings\":[");
    }

    @Nested
    @DisplayName("escaping")
    class Escaping {

        static Stream<Arguments> requiredCharacters() {
            return Stream.of(
                    Arguments.of("a \"quoted\" word", "\"a \\\"quoted\\\" word\""),
                    Arguments.of("a back\\slash", "\"a back\\\\slash\""),
                    Arguments.of("a tab\there", "\"a tab\\there\""),
                    Arguments.of("a newline\nhere", "\"a newline\\nhere\""),
                    Arguments.of("a carriage\rreturn", "\"a carriage\\rreturn\""),
                    Arguments.of("a form\ffeed", "\"a form\\ffeed\""),
                    Arguments.of("a back\bspace", "\"a back\\bspace\""));
        }

        @ParameterizedTest(name = "{0} escapes correctly")
        @MethodSource("requiredCharacters")
        @DisplayName("escapes the characters JSON requires")
        void escapesRequiredCharacters(String input, String expected) {
            assertThat(Json.quote(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("escapes remaining control characters as \\uXXXX")
        void escapesControlCharacters() {
            assertThat(Json.quote("bell:\u0007")).isEqualTo("\"bell:\\u0007\"");
            assertThat(Json.quote("nul:\u0000")).isEqualTo("\"nul:\\u0000\"");
        }

        @Test
        @DisplayName("leaves non-ASCII alone, so the UTF-8 output stays readable")
        void leavesNonAscii() {
            assertThat(Json.quote("transacción ← correcta")).isEqualTo("\"transacción ← correcta\"");
        }

        @Test
        @DisplayName("an empty string is still a valid string")
        void emptyString() {
            assertThat(Json.quote("")).isEqualTo("\"\"");
        }

        @Test
        @DisplayName("a finding message quoting real code survives intact")
        void messageWithCodeQuotes() {
            Finding withQuotes = new Finding("TX001", "transactional", Severity.ERROR, "A.java", 3, 5, 3,
                    "Method \"place()\" calls \"this.charge()\" directly.",
                    "Extract to \"OrderService.place()\" and inject it.");

            assertThat(render(result(List.of(withQuotes), 1)))
                    .contains("Method \\\"place()\\\" calls \\\"this.charge()\\\" directly.")
                    .contains("Extract to \\\"OrderService.place()\\\" and inject it.");
        }
    }

    private static AnalysisResult result(List<Finding> findings, int filesScanned) {
        return new AnalysisResult(findings, filesScanned, 0, Duration.ofMillis(42), "1.2.3");
    }

    private static Finding finding(String file, int line) {
        return finding(file, line, Severity.ERROR);
    }

    private static Finding finding(String file, int line, Severity severity) {
        return new Finding("AOP001", "spring-aop", severity, file, line, 9, line,
                "Something is wrong here.", "Do this instead.");
    }
}
