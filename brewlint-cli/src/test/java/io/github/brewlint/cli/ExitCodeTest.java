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
 * Exit codes are a contract with CI: a build script branches on them without parsing output. These
 * tests pin that contract.
 */
@DisplayName("CLI: exit codes")
class ExitCodeTest {

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

    @Test
    @DisplayName("0 when there are no findings")
    void cleanProject(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Clean.java"), """
                class Clean {
                    public void fine() {}
                }
                """);

        assertThat(run("scan", "--path", projectRoot.toString())).isEqualTo(BrewlintCli.EXIT_CLEAN);
        assertThat(out.toString()).contains("No findings");
    }

    @Test
    @DisplayName("1 when a finding reaches the threshold")
    void findingsFound(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Broken.java"), """
                class Broken {
                    @Transactional
                    private void charge() {}
                }
                """);

        assertThat(run("scan", "--path", projectRoot.toString())).isEqualTo(BrewlintCli.EXIT_FINDINGS);
        assertThat(out.toString()).contains("AOP001");
    }

    @Test
    @DisplayName("--fail-on NONE never fails, whatever the findings")
    void neverFails(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Broken.java"), """
                class Broken {
                    @Transactional
                    private void charge() {}
                }
                """);

        assertThat(run("scan", "--path", projectRoot.toString(), "--fail-on", "none"))
                .isEqualTo(BrewlintCli.EXIT_CLEAN);
        assertThat(out.toString()).contains("AOP001");
    }

    @Test
    @DisplayName("--fail-on INFO is stricter than the default")
    void stricterThreshold(@TempDir Path projectRoot) throws IOException {
        // The finding is demoted to INFO by configuration, so the default ERROR threshold ignores
        // it and only the explicit INFO threshold fails the build.
        Files.writeString(projectRoot.resolve("Demoted.java"), """
                class Demoted {
                    @Transactional
                    private void charge() {}
                }
                """);
        Files.writeString(projectRoot.resolve("brewlint.yml"), """
                rules:
                  AOP001:
                    severity: INFO
                """);

        assertThat(run("scan", "--path", projectRoot.toString())).isEqualTo(BrewlintCli.EXIT_CLEAN);
        assertThat(out.toString()).contains("AOP001").contains("info");

        out.getBuffer().setLength(0);
        assertThat(run("scan", "--path", projectRoot.toString(), "--fail-on", "info"))
                .isEqualTo(BrewlintCli.EXIT_FINDINGS);
    }

    @Test
    @DisplayName("a rule turned off in brewlint.yml cannot fail the build")
    void disabledRuleIsSilent(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Broken.java"), """
                class Broken {
                    @Transactional
                    private void charge() {}
                }
                """);
        Files.writeString(projectRoot.resolve("brewlint.yml"), """
                rules:
                  AOP001: false
                """);

        assertThat(run("scan", "--path", projectRoot.toString())).isEqualTo(BrewlintCli.EXIT_CLEAN);
        assertThat(out.toString()).doesNotContain("AOP001");
    }

    @Test
    @DisplayName("2 when the path does not exist")
    void missingPath() {
        assertThat(run("scan", "--path", "/definitely/not/here")).isEqualTo(BrewlintCli.EXIT_ERROR);
        assertThat(err.toString()).contains("no such file or directory");
    }

    @Test
    @DisplayName("2 when brewlint.yml is malformed, rather than silently using defaults")
    void malformedConfig(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Ok.java"), "class Ok {}");
        Files.writeString(projectRoot.resolve("brewlint.yml"), """
                rules:
                  AOP001:
                    severity: CRITICAL
                """);

        assertThat(run("scan", "--path", projectRoot.toString())).isEqualTo(BrewlintCli.EXIT_ERROR);
        assertThat(err.toString()).contains("CRITICAL");
    }

    @Test
    @DisplayName("2 when brewlint.yml names a rule that does not exist")
    void unknownRuleInConfig(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Ok.java"), "class Ok {}");
        Files.writeString(projectRoot.resolve("brewlint.yml"), """
                rules:
                  NOPE999: false
                """);

        assertThat(run("scan", "--path", projectRoot.toString())).isEqualTo(BrewlintCli.EXIT_ERROR);
        assertThat(err.toString()).contains("NOPE999");
    }

    @Test
    @DisplayName("--no-color output contains no escape sequences")
    void noColorOutputIsPlain(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Broken.java"), """
                class Broken {
                    @Transactional
                    private void charge() {}
                }
                """);

        run("scan", "--path", projectRoot.toString(), "--no-color");

        // Regression guard: the renderer used to emit escape codes regardless of --no-color.
        assertThat(out.toString()).doesNotContain("\u001B").contains("AOP001");
    }

    @Test
    @DisplayName("colour is emitted when it is explicitly forced")
    void forcedColorIsEmitted(@TempDir Path projectRoot) throws IOException {
        Files.writeString(projectRoot.resolve("Broken.java"), """
                class Broken {
                    @Transactional
                    private void charge() {}
                }
                """);

        run("scan", "--path", projectRoot.toString(), "--color");

        assertThat(out.toString()).contains("\u001B[").contains("AOP001");
    }

    @Test
    @DisplayName("--list-rules prints the rules and exits clean")
    void listRules() {
        assertThat(run("scan", "--list-rules", "--path", ".")).isEqualTo(BrewlintCli.EXIT_CLEAN);
        assertThat(out.toString()).contains("AOP001").contains("RES001");
    }

    @Test
    @DisplayName("--help and --version work")
    void helpAndVersion() {
        assertThat(run("--version")).isZero();
        assertThat(out.toString()).contains("brewlint");
        assertThat(run("--help")).isZero();
    }
}
