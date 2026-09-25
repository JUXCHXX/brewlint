package io.github.brewlint.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AI pass must never interfere with the report.
 *
 * <p>These are integration tests at the CLI boundary, because the failure they guard against only
 * exists there: a progress message written to stdout ahead of a JSON document produces output that
 * every parser rejects and every consumer misreads. It is invisible in a terminal, which is
 * exactly why it needs a test.
 */
@DisplayName("CLI: --ai does not disturb the report")
class AiFlagTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private int run(String... args) {
        picocli.CommandLine commandLine = new picocli.CommandLine(new BrewlintCli())
                .setOut(new PrintWriter(out))
                .setErr(new PrintWriter(err));
        int exitCode = commandLine.execute(args);
        commandLine.getOut().flush();
        commandLine.getErr().flush();
        return exitCode;
    }

    private void brokenProject(Path root) throws IOException {
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
    @DisplayName("without --ai, stdout is exactly the report")
    void noFlag(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);

        run("scan", "--path", projectRoot.toString(), "--no-color");

        assertThat(out.toString())
                .contains("AOP001")
                .doesNotContain("AI pass")
                .doesNotContain("asking");
        assertThat(err.toString()).isEmpty();
    }

    @Test
    @DisplayName("--ai with an unconfigured provider warns on stderr and leaves stdout alone")
    void unconfiguredProviderWarnsOnStderr(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);

        run("scan", "--path", projectRoot.toString(), "--no-color",
                "--ai", "anthropic", "--fail-on", "none");

        // The finding is still there, and stdout has no progress chatter mixed into it.
        assertThat(out.toString()).contains("AOP001").doesNotContain("AI pass skipped");
        assertThat(err.toString()).contains("AI pass skipped").contains("not configured");
    }

    @Test
    @DisplayName("an unknown provider name is reported, not swallowed")
    void unknownProvider(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);

        run("scan", "--path", projectRoot.toString(), "--no-color", "--ai", "magic");

        assertThat(err.toString())
                .contains("unknown provider 'magic'")
                .contains("anthropic, ollama");
    }

    @Test
    @DisplayName("an unreachable provider does not change the exit code")
    void unreachableProviderKeepsExitCode(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);

        // Ollama is not running in CI, which is exactly the case this covers: a failing optional
        // pass must leave the build's verdict alone.
        int withoutAi = run("scan", "--path", projectRoot.toString(), "--no-color");
        out.getBuffer().setLength(0);

        int withAi = run("scan", "--path", projectRoot.toString(), "--no-color", "--ai", "ollama");

        assertThat(withAi).isEqualTo(withoutAi).isEqualTo(BrewlintCli.EXIT_FINDINGS);
        assertThat(err.toString()).contains("AI pass failed");
    }

    @Test
    @DisplayName("JSON output stays valid JSON when an AI pass runs and fails")
    void jsonStaysValidWhenAiFails(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);

        run("scan", "--path", projectRoot.toString(), "--format", "json", "--ai", "ollama");

        String json = out.toString();
        // The exact thing a consumer would choke on: a status line ahead of the document.
        assertThat(json).startsWith("{").doesNotContain("asking");
        assertThat(json).contains("\"ruleId\":\"AOP001\"");
        // And the source is present, so a consumer can tell a rule finding from a suggestion.
        assertThat(json).contains("\"source\":\"RULE\"");
        assertThat(json.strip().endsWith("}")).isTrue();
    }

    @Test
    @DisplayName("the AI budget is validated, so a typo cannot silently cost nothing")
    void budgetOptionsExist(@TempDir Path projectRoot) throws IOException {
        brokenProject(projectRoot);

        run("scan", "--path", projectRoot.toString(), "--no-color",
                "--ai", "ollama", "--ai-max-findings", "5", "--ai-max-lines", "100");

        assertThat(err.toString()).contains("AI pass failed");
    }
}
