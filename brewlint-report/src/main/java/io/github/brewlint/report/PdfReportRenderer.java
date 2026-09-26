package io.github.brewlint.report;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import io.github.brewlint.core.Brewlint;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.FindingSource;
import io.github.brewlint.core.model.Severity;

import java.io.IOException;
import java.io.OutputStream;
import javax.xml.XMLConstants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The shareable report: a PDF somebody can attach to a pull request or send to a team.
 *
 * <p>Built by rendering HTML with OpenHTMLtoPDF rather than placing every line by hand. The reason
 * is wrapping. A finding message, a file path and a Java identifier all have to wrap at a sensible
 * point and keep a monospace excerpt aligned, and a text-drawing API gives neither for free. The
 * whole document is therefore a string builder, and the PDF step is one call at the end.
 *
 * <h2>Colour is never used to carry meaning alone</h2>
 * A red error and a green line are identical to a reader with any form of colour blindness, and a
 * report that only works for people who see colour is a report that does not work. Every severity
 * is written out in words as well as coloured, and the summary is a table of numbers, not a row of
 * coloured boxes.
 */
public final class PdfReportRenderer implements ReportRenderer {

    /** Findings of this severity or higher are shown in full detail. */
    private static final int DEFAULT_MAX_DETAILED = 200;

    /** One-shot guard around the reflective logging configuration. */
    private static boolean LOGGING_SILENCED;

    private final int maxDetailed;

    public PdfReportRenderer() {
        this(DEFAULT_MAX_DETAILED);
    }

    public PdfReportRenderer(int maxDetailed) {
        this.maxDetailed = maxDetailed;
    }

    @Override
    public String format() {
        return "pdf";
    }

    /**
     * A PDF is binary, so this renders into a file and writes nothing to the target.
     *
     * <p>Throwing rather than writing bytes to an {@code Appendable} is deliberate: silently
     * producing mojibake in a {@code StringBuilder} because someone called {@code render} instead of
     * {@link #write} is worse than a clear failure.
     */
    @Override
    public void render(AnalysisResult result, ReportOptions options, Appendable target)
            throws IOException {
        throw new UnsupportedOperationException(
                "A PDF is binary. Use PdfReportRenderer.write(Path) instead of render(..).");
    }

    /** Renders to {@code destination}, creating parent directories. */
    public void write(AnalysisResult result, Path destination) throws IOException {
        Path parent = destination.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Before the render, not in a finally: XRLog reads its level lazily on first use, so a
        // later call would arrive after the lines it is meant to suppress have already been printed.
        silenceRendererLogging();
        try (OutputStream out = Files.newOutputStream(destination)) {
            write(result, out);
        }
    }

    /** Renders to any stream. Useful for tests and for callers that want the bytes. */
    public void write(AnalysisResult result, OutputStream out) throws IOException {
        // Idempotent, and called from write(Path) already. Harmless here for a caller that goes
        // straight to a stream, which is the case a test does.
        silenceRendererLogging();
        org.w3c.dom.Document document = parse(html(result));
        try {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // Analysis, not generation. The report is a page of text, and running the full
            // resolution matrix for it would dominate the time the scan itself takes.
            builder.useFastMode();
            // The base URL is null: the document is self-contained, with no images or stylesheets to
            // resolve. Passing anything else would make the renderer go looking for a file.
            builder.withW3cDocument(document, null);
            builder.toStream(out);
            builder.run();
        } catch (IOException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IOException("could not render the PDF: " + exception.getMessage(), exception);
        }
    }

    /**
     * Turns off the renderer's own chatter, once per run.
     *
     * <p>OpenHTMLtopdf prints three lines to stderr on every render, through its own {@code XRLog}
     * rather than through SLF4J:
     *
     * <pre>
     * com.openhtmltopdf.load INFO:: TIME: parse stylesheets 38ms
     * com.openhtmltopdf.match INFO:: media = print
     * com.openhtmltopdf.general INFO:: Using fast-mode renderer.
     * </pre>
     *
     * <p>In a CLI that is the first thing a user sees, on a command whose job is to say cleanly
     * whether the code is fine. It also lands on stderr, which {@code --format json} is
     * contractually required to keep free of chatter.
     *
     * <p>Done before the render rather than after, and only once: {@code XRLog} reads its level
     * lazily the first time it logs, so a call in a {@code finally} block would arrive too late to
     * suppress the very lines it is aimed at.
     *
     * <p>Reflected rather than called directly, so {@code openhtmltopdf-core} stays out of this
     * module's compile classpath. The PDF layout is worth that dependency; a log formatter is not.
     * If the class or the method moves, the call quietly stops working and the report still renders,
     * which is the right failure mode for "make it quieter".
     */
    private static void silenceRendererLogging() {
        if (LOGGING_SILENCED) {
            return;
        }
        LOGGING_SILENCED = true;
        try {
            Class<?> log = Class.forName("com.openhtmltopdf.util.XRLog");
            log.getMethod("setLoggingEnabled", boolean.class).invoke(null, false);
            java.util.logging.Logger.getLogger("com.openhtmltopdf")
                    .setLevel(java.util.logging.Level.WARNING);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Logging turned itself off in a future version, or the class moved. Nothing to do.
        }
    }

    private static void silenceRendererLoggingOnce() {
        if (LOGGING_SILENCED) {
            return;
        }
        LOGGING_SILENCED = true;
        try {
            Class<?> log = Class.forName("com.openhtmltopdf.util.XRLog");
            log.getMethod("setLoggingEnabled", boolean.class).invoke(null, false);
            java.util.logging.Logger.getLogger("com.openhtmltopdf")
                    .setLevel(java.util.logging.Level.WARNING);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Logging turned itself off in a future version, or the class moved. Nothing to do.
        }
    }


    /**
     * Parses the report XHTML into a DOM.
     *
     * <p>No external parser: the JDK's own is used through {@code DocumentBuilderFactory}. Secure
     * processing is on and DOCTYPE declarations are disallowed, so the document is parsed as plain
     * markup with no external entity resolution. That is both the safe configuration and the one
     * this generated document is written for.
     *
     * <p>Non-fatal parser warnings are silenced on purpose. The JDK's XHTML parser objects to a
     * named HTML entity such as {@code &nbsp;} when no DOCTYPE is present, and every one of those
     * warnings concerns a character that has already been escaped. Turning them into errors would
     * mean either dropping the entities or weakening the parser, and neither is a trade worth making
     * for a report.
     */
    private static org.w3c.dom.Document parse(String markup) throws IOException {
        try {
            javax.xml.parsers.DocumentBuilderFactory factory =
                    javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setNamespaceAware(true);

            javax.xml.parsers.DocumentBuilder builder = factory.newDocumentBuilder();
            // An empty handler: the default prints every warning to stderr, and a report that
            // prints parse noise to the console looks like it failed.
            builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException exception) {
                    // Ignored, as described above.
                }

                @Override
                public void error(org.xml.sax.SAXParseException exception) {
                    // A real error still propagates: DocumentBuilder.parse calls fatalError.
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException exception)
                        throws org.xml.sax.SAXException {
                    throw exception;
                }
            });

            return builder.parse(new org.xml.sax.InputSource(
                    new java.io.ByteArrayInputStream(markup.getBytes(StandardCharsets.UTF_8))));
        } catch (org.xml.sax.SAXException exception) {
            throw new IOException("could not build the report document: " + exception.getMessage(),
                    exception);
        } catch (Exception exception) {
            throw new IOException("could not build the report document: " + exception.getMessage(),
                    exception);
        }
    }

    /** The report as HTML, exposed so it can be tested and inspected without rendering a PDF. */
    public String html(AnalysisResult result) {
        Html html = new Html();
        head(html, result);
        summary(html, result);
        findings(html, result);
        if (result.filesWithParseErrors() > 0) {
            notice(html, result.filesWithParseErrors()
                    + " file(s) had parse problems, so findings in them may be incomplete.");
        }
        html.close();
        return html.toString();
    }

    // ---- document ---------------------------------------------------------------------------

    private void head(Html html, AnalysisResult result) {
        // No DOCTYPE. The document is parsed with doctype declarations disallowed, which is the
        // correct setting for a parser pointed at our own generated markup: a DOCTYPE here would
        // enable external entity resolution for no benefit and be rejected by the parser anyway.
        // This is XHTML, so the namespace is what makes it well-formed.
        html.raw("<html xmlns=\"http://www.w3.org/1999/xhtml\"><head>"
                + "<meta charset=\"utf-8\"/><title>Brewlint report</title>");
        html.raw("<style type=\"text/css\">").raw(CSS).raw("</style></head><body>");
        html.raw("<h1>Brewlint</h1>");
        html.raw("<p class=\"muted\">")
                .text(Brewlint.NAME + " " + result.toolVersion())
                .text(" \u00b7 generated " + result.duration().toMillis() + "ms after scanning ")
                .text(result.filesScanned() == 1 ? "1 file." : result.filesScanned() + " files.")
                .raw("</p>");
    }

    private void summary(Html html, AnalysisResult result) {
        html.raw("<h2>Summary</h2>");

        // A table of numbers, not a row of coloured boxes. Someone photocopying this report, or
        // reading it with a screen reader, gets the same information either way.
        html.raw("<table class=\"counts\"><tr>");
        cell(html, "Total", result.findings().size(), "");
        cell(html, "Errors", result.count(Severity.ERROR), "error");
        cell(html, "Warnings", result.count(Severity.WARNING), "warning");
        cell(html, "Info", result.count(Severity.INFO), "info");
        html.raw("</tr></table>");
    }

    private void cell(Html html, String label, long value, String kind) {
        html.raw("<td class=\"count" + (kind.isEmpty() ? "" : " " + kind) + "\">")
                .raw("<span class=\"number\">").text(Long.toString(value)).raw("</span>")
                .raw("<span class=\"label\">").text(label).raw("</span>")
                .raw("</td>");
    }

    private void findings(Html html, AnalysisResult result) {
        if (result.findings().isEmpty()) {
            html.raw("<h2>Findings</h2><p class=\"ok\">No findings.</p>");
            return;
        }

        Map<String, List<Finding>> byFile = new LinkedHashMap<>();
        for (Finding finding : result.findings()) {
            byFile.computeIfAbsent(finding.filePath(), key -> new ArrayList<>()).add(finding);
        }

        html.raw("<h2>Findings</h2>");
        for (Map.Entry<String, List<Finding>> entry : byFile.entrySet()) {
            html.raw("<h3 class=\"file\">").text(entry.getKey()).raw("</h3>");
            for (Finding finding : entry.getValue()) {
                finding(html, finding);
            }
        }
    }

    private void finding(Html html, Finding finding) {
        html.raw("<div class=\"finding\">");
        html.raw("<div class=\"head\">");
        html.raw("<span class=\"sev ").raw(finding.severity().name().toLowerCase(java.util.Locale.ROOT))
                .raw("\">").text(finding.severity().name()).raw("</span>");
        // The rule id, the line and the source are the three things a reader needs to go and look.
        html.raw("<span class=\"rule\">").text(finding.ruleId()).raw("</span>");
        html.raw("<span class=\"loc\">").text(finding.line() + ":" + finding.column()).raw("</span>");
        if (finding.source() == FindingSource.AI) {
            // Stated in words, not implied by a colour. An unverified suggestion has to look
            // unverified to someone printing this in black and white.
            html.raw("<span class=\"tag\">suggested by a model, unverified</span>");
        }
        html.raw("</div>");
        html.raw("<p class=\"message\">").text(finding.message()).raw("</p>");
        // A literal arrow rather than &rarr;. The document is parsed as XML with DOCTYPE declarations
        // disallowed, which means no entity table, and a named entity is a fatal error there. Only
        // the five XML predefined entities exist in that configuration, and this arrow is a
        // character in the report, not markup.
        html.raw("<p class=\"fix\"><span class=\"arrow\">\u2192</span>")
                .text(finding.suggestion()).raw("</p>");
        html.raw("</div>");
    }

    private void notice(Html html, String message) {
        html.raw("<p class=\"notice\">").text(message).raw("</p>");
    }

    /**
     * A4 portrait with room for a message and a suggestion, and a monospace font for paths and ids.
     * A4 rather than US Letter because most of the world outside North America prints A4, and this
     * is a report meant to be printed.
     */
    private static final String CSS = """
            @page { size: A4 portrait; margin: 18mm 16mm; }
            body { font-family: Helvetica, Arial, sans-serif; font-size: 9.5pt; color: #16191d; }
            h1 { font-size: 17pt; margin: 0 0 2mm; }
            h2 { font-size: 12pt; margin: 7mm 0 3mm; border-bottom: 1px solid #d5d9de; padding-bottom: 1.5mm; }
            h3.file { font-size: 10pt; font-family: Menlo, Consolas, monospace; margin: 5mm 0 2mm; }
            .muted { color: #61676e; font-size: 8.5pt; margin: 0 0 4mm; }
            table.counts { width: 100%; border-collapse: collapse; margin-bottom: 3mm; }
            td.count { border: 1px solid #d5d9de; padding: 3mm 4mm; width: 25%; }
            .number { display: block; font-size: 16pt; font-weight: bold; }
            .label { display: block; color: #61676e; font-size: 8pt; text-transform: uppercase;
                     letter-spacing: 0.4pt; }
            .count.error .number { color: #b3261e; }
            .count.warning .number { color: #8a5a00; }
            .count.info .number { color: #0b5cad; }
            .finding { border-left: 2.5pt solid #d5d9de; padding: 1.5mm 0 1.5mm 3.5mm; margin-bottom: 3mm; }
            .finding .head { margin-bottom: 1.5mm; }
            .sev { display: inline-block; font-size: 7.5pt; font-weight: bold; padding: 0.6mm 1.8mm;
                   border-radius: 1.5pt; margin-right: 2.5mm; }
            .sev.error { background: #fbe9e7; color: #b3261e; }
            .sev.warning { background: #fdf3e0; color: #8a5a00; }
            .sev.info { background: #e8f1fb; color: #0b5cad; }
            .rule { font-family: Menlo, Consolas, monospace; font-size: 8.5pt; }
            .loc { font-family: Menlo, Consolas, monospace; font-size: 8.5pt; color: #61676e;
                   margin-left: 3mm; }
            .tag { font-size: 7.5pt; color: #61676e; font-style: italic; margin-left: 3mm; }
            .message { margin: 0 0 1.5mm; }
            .fix { margin: 0; color: #0b5cad; }
            .arrow { font-weight: bold; }
            .ok { color: #0b6b3a; }
            .notice { color: #8a5a00; font-size: 8.5pt; }
            """;

    /** A tiny HTML builder, so every text value is escaped exactly once and in one place. */
    private static final class Html {

        private final StringBuilder out = new StringBuilder();

        Html raw(String markup) {
            out.append(markup);
            return this;
        }

        Html text(String value) {
            out.append(escape(value));
            return this;
        }

        void close() {
            out.append("</body></html>");
        }

        /**
         * XHTML requires every void element to be closed, so the handful of tags that are void in
         * HTML have to be self-closing here or the parser rejects the document.
         */
        Html voidElement(String name) {
            return raw("<" + name + "/>");
        }

        @Override
        public String toString() {
            return out.toString();
        }

        /**
         * Escapes the five characters that matter. A file path can contain an ampersand and a
         * finding message can contain a quote, and one unescaped character produces a PDF that
         * silently loses the rest of the line.
         */
        static String escape(String value) {
            StringBuilder escaped = new StringBuilder(value.length() + 16);
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                switch (character) {
                    case '&' -> escaped.append("&amp;");
                    case '<' -> escaped.append("&lt;");
                    case '>' -> escaped.append("&gt;");
                    case '"' -> escaped.append("&quot;");
                    case '\'' -> escaped.append("&#39;");
                    default -> escaped.append(character);
                }
            }
            return escaped.toString();
        }
    }
}
