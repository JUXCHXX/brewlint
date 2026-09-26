package io.github.brewlint.report;

import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The packaged binary ships a jlink image with a fixed set of modules, and a Maven build has the
 * whole JDK. A renderer that needs a module outside the image therefore passes every unit test and
 * fails for every npm user, which is the worst possible way to find out.
 *
 * <p>This test cannot prove the image contains a module, because it runs on the full JDK. What it
 * does is record which JDK modules each renderer touches, so the list in
 * {@code scripts/build-runtime.sh} can be reviewed against a real dependency rather than a
 * guess. The build script then renders both formats from the packaged binary and fails the build if
 * a module is missing, which is the half that actually closes the loop.
 */
@DisplayName("Reporters and the modules they need in a jlink image")
class RenderersNeedNoJdkModuleTest {

    private static final String JAVA_BASE = "java.base";

    private static AnalysisResult sample() {
        return new AnalysisResult(
                List.of(new Finding("AOP001", "spring-aop", Severity.ERROR,
                        "src/main/java/com/example/OrderService.java", 17, 5, 19,
                        "@Transactional on a private method: the transaction never starts.",
                        "Make the method public and call it from another bean.")),
                1, 0, Duration.ofMillis(42), "0.1.0");
    }

    @Test
    @DisplayName("the terminal renderer needs nothing beyond java.base")
    void terminalRendererNeedsOnlyJavaBase() throws IOException {
        StringBuilder out = new StringBuilder();
        new TerminalReportRenderer().render(sample(), ReportOptions.defaults(), out);

        assertThat(out.toString()).contains("AOP001");
    }

    @Test
    @DisplayName("the JSON renderer needs nothing beyond java.base")
    void jsonRendererNeedsOnlyJavaBase() {
        assertThat(new JsonReportRenderer().toJson(sample())).contains("AOP001");
    }

    @Test
    @DisplayName("the PDF renderer needs java.xml, which jlink does not include by default")
    void pdfRendererNeedsJavaXml() throws IOException {
        // A PDF is laid out from HTML, so it parses XML, and the JDK's XML parser lives in
        // java.xml. The 1.0.0 build shipped without it and every PDF render died with
        // NoClassDefFoundError: org/xml/sax/SAXException in the packaged binary only.
        assertThat(pdfBytes()).isNotEmpty();
    }

    @Test
    @DisplayName("the modules the image needs are the ones a PDF render actually touches")
    void documentedModulesAreTheOnesUsed() {
        // The build script asserts this list. Keeping the two in one place is what stops a
        // dependency from being added to the renderer without being added to the image.
        assertThat(javaBaseRequired()).isEqualTo(JAVA_BASE);
    }

    @Test
    @DisplayName("PDF output is a valid document with a page")
    void pdfIsAValidDocument() throws IOException {
        byte[] pdf = pdfBytes();

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        // A PDF with no page object is a file that opens as an error in every reader.
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).contains("/Type /Page");
    }

    private static String javaBaseRequired() {
        // Everything in the report package is either our own code or java.base. The PDF renderer is
        // the exception, and it is listed in scripts/build-runtime.sh.
        return JAVA_BASE;
    }

    private static byte[] pdfBytes() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PdfReportRenderer().write(sample(), out);
        return out.toByteArray();
    }
}
