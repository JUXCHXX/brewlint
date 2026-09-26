package io.github.brewlint.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code --format pdf} from the command line.
 *
 * <p>The interesting part is not that a PDF is produced, which the renderer tests cover, but that the
 * CLI sends the bytes to a file rather than to a terminal, and that a failure to render is reported
 * as an error instead of a stack trace.
 */
@DisplayName("CLI: --format pdf")
class PdfFlagTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        picocli.CommandLine commandLine = BrewlintCli.commandLine()
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int exitCode = commandLine.execute(args);
        commandLine.getOut().flush();
        commandLine.getErr().flush();
        return exitCode;
    }

    private static void brokenProject(Path root) throws IOException {
        Files.writeString(root.resolve("OrderService.java"), """
                package com.example;
                import org.springframework.stereotype.Service;
                import org.springframework.transaction.annotation.Transactional;
                @Service
                public class OrderService {
                    @Transactional
                    private void charge() {}
                }
                """);
    }

    @Test
    @DisplayName("writes a real PDF to --output and keeps stdout clean")
    void writesToOutput(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);
        Path destination = projectRoot.resolve("report.pdf");

        int exitCode = run("scan", "--path", projectRoot.toString(),
                "--format", "pdf", "--output", destination.toString(), "--no-color");

        assertThat(destination).exists();
        assertThat(Files.readAllBytes(destination))
                .startsWith("%PDF-".getBytes(StandardCharsets.US_ASCII));
        // stdout must stay free of binary. A PDF written to a terminal corrupts the terminal.
        assertThat(out.toString()).contains("Wrote 1 finding").doesNotContain("%PDF");
        assertThat(err.toString()).isEmpty();
        assertThat(exitCode).isEqualTo(BrewlintCli.EXIT_FINDINGS);
    }

    @Test
    @DisplayName("creates the parent directory rather than failing")
    void createsParentDirectory(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);
        Path destination = projectRoot.resolve("out/reports/brewlint.pdf");

        run("scan", "--path", projectRoot.toString(), "--format", "pdf",
                "--output", destination.toString(), "--no-color");

        assertThat(destination).exists();
    }

    @Test
    @DisplayName("falls back to a default file name when --output is omitted")
    void defaultDestination(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);

        // run() executes in the JVM's working directory, so the default lands there. Asserted on the
        // message rather than the file, because the file is not ours to clean up reliably.
        run("scan", "--path", projectRoot.toString(), "--format", "pdf", "--no-color");

        assertThat(out.toString()).contains("brewlint-report.pdf");
    }

    @Test
    @DisplayName("a clean project still produces a PDF")
    void cleanProject(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Clean.java"), "class Clean {}");
        Path destination = projectRoot.resolve("clean.pdf");

        int exitCode = run("scan", "--path", projectRoot.toString(),
                "--format", "pdf", "--output", destination.toString(), "--no-color");

        assertThat(destination).exists();
        assertThat(out.toString()).contains("Wrote 0 findings");
        assertThat(exitCode).isEqualTo(BrewlintCli.EXIT_CLEAN);
    }

    @Test
    @DisplayName("the exit code still reflects the findings, not the format")
    void exitCodeIsUnaffectedByFormat(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);

        int withThreshold = run("scan", "--path", projectRoot.toString(),
                "--format", "pdf", "--output", projectRoot.resolve("a.pdf").toString());
        out.getBuffer().setLength(0);
        int withoutThreshold = run("scan", "--path", projectRoot.toString(),
                "--format", "pdf", "--output", projectRoot.resolve("b.pdf").toString(),
                "--fail-on", "none");

        assertThat(withThreshold).isEqualTo(BrewlintCli.EXIT_FINDINGS);
        assertThat(withoutThreshold).isEqualTo(BrewlintCli.EXIT_CLEAN);
    }

    @Test
    @DisplayName("an unknown format names the real options")
    void unknownFormat(@TempDir Path projectRoot) {
        int exitCode = run("scan", "--path", projectRoot.toString(), "--format", "postscript");

        assertThat(err.toString()).contains("terminal, json, pdf");
        assertThat(exitCode).isEqualTo(BrewlintCli.EXIT_ERROR);
    }

    @Test
    @DisplayName("the format is validated before any scanning happens")
    void unknownFormatFailsFast(@TempDir Path projectRoot) throws IOException {
        // A typo in --format should not cost a full scan of the project, and should not leave
        // anything behind on disk.
        run("scan", "--path", projectRoot.toString(), "--format", "postscript");

        assertThat(err.toString()).containsIgnoringCase("unknown format");
        try (var entries = Files.list(projectRoot)) {
            assertThat(entries).isEmpty();
        }
    }
}
