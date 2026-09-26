package io.github.brewlint.report;

import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.FindingSource;
import io.github.brewlint.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("PdfReportRenderer: the shareable report")
class PdfReportRendererTest {

    private final PdfReportRenderer renderer = new PdfReportRenderer();

    private static AnalysisResult result() {
        return new AnalysisResult(
                List.of(
                        new Finding("AOP001", "spring-aop", Severity.ERROR,
                                "src/main/java/com/example/OrderService.java", 17, 5, 19,
                                "@Transactional on a private method: Spring's proxy cannot "
                                        + "intercept it, so the transaction is never started.",
                                "Make the method public and call it from another bean."),
                        new Finding("BEAN003", "bean", Severity.INFO,
                                "src/main/java/com/example/OrderService.java", 22, 5, 22,
                                "Field gateway is injected with @Autowired.",
                                "Make the field final and inject it through the constructor."),
                        new Finding("AI001", "ai", Severity.WARNING,
                                "src/main/java/com/example/OrderService.java", 31, 1, 31,
                                "The cache is never invalidated anywhere in this class.",
                                "Add @CacheEvict to the write path.",
                                FindingSource.AI)),
                1, 0, Duration.ofMillis(42), "0.1.0");
    }

    @Nested
    @DisplayName("the document")
    class Document {

        @Test
        @DisplayName("is real PDF bytes, not a file that happens to be named .pdf")
        void isRealPdf() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            renderer.write(result(), out);

            byte[] bytes = out.toByteArray();
            assertThat(bytes.length).isGreaterThan(1000);
            // The magic number. A renderer that silently wrote nothing would still "succeed".
            assertThat(new String(bytes, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
            assertThat(new String(bytes, bytes.length - 7, 7, StandardCharsets.US_ASCII))
                    .contains("%%EOF");
        }

        @Test
        @DisplayName("is a PDF with content, not a valid but empty document")
        void hasContent() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            renderer.write(result(), out);

            // PDFBox compresses the content streams, so the text is not reliably greppable. What is
            // checkable is that a report with three findings is substantially larger than the
            // boilerplate of an empty one, which is the failure mode of a renderer that renders
            // nothing and still exits cleanly.
            int withFindings = out.size();

            ByteArrayOutputStream empty = new ByteArrayOutputStream();
            renderer.write(new AnalysisResult(List.of(), 0, 0, Duration.ZERO, "0.1.0"), empty);

            assertThat(withFindings).isGreaterThan(empty.size());
        }

        @Test
        @DisplayName("writes to a file, creating parent directories")
        void writesToFile(@TempDir Path directory) throws IOException {
            Path destination = directory.resolve("nested/reports/brewlint.pdf");

            renderer.write(result(), destination);

            assertThat(destination).exists();
            assertThat(Files.readAllBytes(destination)).startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("refuses to render into an Appendable instead of writing mojibake")
        void refusesAppendable() {
            // A PDF is binary. Writing it into a StringBuilder would produce something that looks
            // like it worked and is not a PDF at all.
            assertThatThrownBy(() -> renderer.render(result(), ReportOptions.defaults(),
                    new StringWriter()))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("binary");
        }

        @Test
        @DisplayName("handles a clean project")
        void cleanProject() throws IOException {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            renderer.write(new AnalysisResult(List.of(), 3, 0, Duration.ZERO, "0.1.0"), out);

            assertThat(new String(out.toByteArray(), 0, 5, StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF-");
        }

        @Test
        @DisplayName("handles a project with parse problems")
        void parseProblems() throws IOException {
            AnalysisResult withProblems = new AnalysisResult(
                    List.of(), 2, 2, Duration.ZERO, "0.1.0");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            renderer.write(withProblems, out);

            assertThat(new String(out.toByteArray(), 0, 5, StandardCharsets.US_ASCII))
                    .isEqualTo("%PDF-");
        }
    }

    @Nested
    @DisplayName("the HTML")
    class Html {

        @Test
        @DisplayName("names the file, the rule and the line, which is what a reader acts on")
        void hasTheActionableParts() {
            String html = renderer.html(result());

            assertThat(html)
                    .contains("OrderService.java")
                    .contains("AOP001")
                    .contains("17:5")
                    .contains("Make the method public");
        }

        @Test
        @DisplayName("counts by severity in words, not only in colour")
        void severityIsWrittenOut() {
            String html = renderer.html(result());

            // A report that only works for people who see colour is a report that does not work.
            assertThat(html).contains("ERROR").contains("WARNING").contains("INFO");
            assertThat(html).contains(">Errors<").contains(">Warnings<");
        }

        @Test
        @DisplayName("marks an AI finding as unverified in words")
        void aiIsLabelled() {
            String html = renderer.html(result());

            assertThat(html).contains("suggested by a model, unverified");
        }

        @Test
        @DisplayName("says so when there is nothing to report")
        void cleanReport() {
            String html = renderer.html(
                    new AnalysisResult(List.of(), 0, 0, Duration.ZERO, "0.1.0"));

            assertThat(html).contains("No findings");
        }

        @Test
        @DisplayName("escapes text so a message cannot break the document")
        void escapesMarkup() {
            Finding hostile = new Finding("X001", "test", Severity.ERROR, "A<b>.java", 1, 1, 1,
                    "message with <script>alert(1)</script> and a \"quote\" and an & ampersand",
                    "suggestion with <b>bold</b> & more");

            String html = renderer.html(new AnalysisResult(List.of(hostile), 1, 0, Duration.ZERO, "1"));

            assertThat(html)
                    .doesNotContain("<script>")
                    .doesNotContain("<b>bold</b>")
                    .contains("&lt;script&gt;")
                    .contains("&amp;")
                    .contains("&quot;");
        }

        @Test
        @DisplayName("escapes a path that contains markup characters")
        void escapesTheFilePath() {
            Finding odd = new Finding("X001", "test", Severity.ERROR,
                    "src/<module>/A&\"B\".java", 1, 1, 1, "message", "suggestion");

            assertThat(renderer.html(new AnalysisResult(List.of(odd), 1, 0, Duration.ZERO, "1")))
                    .doesNotContain("<module>")
                    .contains("&lt;module&gt;");
        }

        @Test
        @DisplayName("groups findings under their file")
        void groupsByFile() {
            String html = renderer.html(result());

            assertThat(html).contains("<h3 class=\"file\">");
            assertThat(html.indexOf("OrderService.java")).isLessThan(html.indexOf("BEAN003"));
        }

        @Test
        @DisplayName("is a complete, well-formed XHTML document")
        void isComplete() {
            String html = renderer.html(result());

            // No DOCTYPE on purpose: the document is parsed with doctype declarations disallowed,
            // and one would be rejected. XHTML with a namespace is what makes it well-formed.
            assertThat(html)
                    .startsWith("<html xmlns=\"http://www.w3.org/1999/xhtml\">")
                    .contains("<meta charset=\"utf-8\"/>")
                    .endsWith("</body></html>");
        }
    }

    @Test
    @DisplayName("identifies itself as pdf")
    void format() {
        assertThat(renderer.format()).isEqualTo("pdf");
    }
}
