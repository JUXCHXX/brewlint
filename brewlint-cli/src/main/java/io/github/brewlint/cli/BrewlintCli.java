package io.github.brewlint.cli;

import io.github.brewlint.core.Brewlint;
import io.github.brewlint.core.config.BrewlintConfig;
import io.github.brewlint.core.config.ConfigLoader;
import io.github.brewlint.core.engine.AnalysisEngine;
import io.github.brewlint.core.engine.SourceCollector;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.report.ReportOptions;
import io.github.brewlint.report.ReportRenderer;
import io.github.brewlint.report.TerminalReportRenderer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * {@code brewlint scan [PATH]}.
 *
 * <p>Deliberately thin: collect files, hand them to the engine, render the result, translate the
 * finding count into an exit code. All analysis lives in {@code brewlint-core}, which is what lets
 * the VS Code extension and the GitHub Action reuse the same engine.
 */
@Command(
        name = Brewlint.NAME,
        mixinStandardHelpOptions = true,
        versionProvider = BrewlintCli.VersionProvider.class,
        description = "Static analysis for Spring Boot anti-patterns.",
        subcommands = {BrewlintCli.ScanCommand.class})
public final class BrewlintCli implements Callable<Integer> {

    /**
     * Exit codes are a contract with CI. A build script should be able to branch on them without
     * parsing output.
     */
    public static final int EXIT_CLEAN = 0;
    public static final int EXIT_FINDINGS = 1;
    public static final int EXIT_ERROR = 2;

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() {
        // No subcommand: show usage rather than silently doing nothing.
        spec.commandLine().usage(System.out);
        return EXIT_CLEAN;
    }

    /** {@code brewlint scan} */
    @Command(name = "scan", description = "Analyse a project and report Spring anti-patterns.")
    public static final class ScanCommand implements Callable<Integer> {

        @Option(names = "--fail-on",
                paramLabel = "<severity>",
                description = "Exit with code 1 when a finding is at least this severe. "
                        + "One of ERROR, WARNING, INFO, NONE. Default: ERROR.")
        String failOn = "ERROR";

        @Option(names = "--no-color", description = "Never emit ANSI escape sequences.")
        boolean noColor;

        @Option(names = "--color", description = "Always emit ANSI escape sequences, even when piped.")
        boolean forceColor;

        @Option(names = "--max-findings",
                paramLabel = "<n>",
                description = "Print at most this many findings. The summary always counts them all.")
        Integer maxFindings;

        @Option(names = {"-c", "--config"},
                paramLabel = "<file>",
                description = "Path to brewlint.yml. Default: <path>/brewlint.yml.")
        Path configFile;

        @Option(names = "--list-rules", description = "Print every rule with its default severity and exit.")
        boolean listRules;

        @Option(names = {"-p", "--path"},
                paramLabel = "<dir>",
                defaultValue = ".",
                description = "Project directory or single .java file to analyse. Default: current directory.")
        Path path;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        private final ReportRenderer terminalRenderer = new TerminalReportRenderer();

        @Override
        public Integer call() {
            return run();
        }

        public Integer run() {
            Path projectRoot = path.toAbsolutePath().normalize();

            if (listRules) {
                printRules(projectRoot);
                return EXIT_CLEAN;
            }

            if (!Files.exists(projectRoot)) {
                spec.commandLine().getErr().println("brewlint: no such file or directory: " + path);
                return EXIT_ERROR;
            }

            try {
                BrewlintConfig config = loadConfig(projectRoot);
                List<Path> files = SourceCollector.collectJavaFiles(projectRoot);
                if (files.isEmpty()) {
                    spec.commandLine().getOut().println(
                            "brewlint: no .java files found under " + path);
                    return EXIT_CLEAN;
                }

                AnalysisEngine engine =
                        AnalysisEngine.withDefaultRules(projectRoot, config, Brewlint.version());
                AnalysisResult result = engine.analyze(files);

                ReportOptions options = new ReportOptions(
                        AnsiSupport.isColorEnabled(noColor, forceColor),
                        maxFindings,
                        ReportOptions.DEFAULT_WIDTH);
                PrintWriter writer = spec.commandLine().getOut();
                terminalRenderer.render(result, options, writer);
                writer.flush();

                return fails(result, failOn) ? EXIT_FINDINGS : EXIT_CLEAN;

            } catch (IOException | IllegalArgumentException exception) {
                spec.commandLine().getErr().println("brewlint: " + exception.getMessage());
                spec.commandLine().getErr().flush();
                return EXIT_ERROR;
            }
        }

        /**
         * {@code NONE} is a threshold no finding can reach, so the build always passes. Parsing
         * happens here rather than in a picocli converter so an unknown value produces the same
         * clean "exit 2 with a message" as every other configuration error.
         */
        private boolean fails(AnalysisResult result, String threshold) {
            if ("none".equalsIgnoreCase(threshold.trim())) {
                return false;
            }
            return result.countAtLeast(Severity.parse(threshold)) > 0;
        }

        private BrewlintConfig loadConfig(Path projectRoot) throws IOException {
            if (configFile != null) {
                if (!Files.isRegularFile(configFile)) {
                    throw new IOException("config file not found: " + configFile);
                }
                return ConfigLoader.load(configFile.getParent() == null
                        ? Path.of(".")
                        : configFile.getParent());
            }
            return ConfigLoader.load(projectRoot);
        }

        private void printRules(Path projectRoot) {
            BrewlintConfig config;
            try {
                config = ConfigLoader.load(projectRoot);
            } catch (RuntimeException exception) {
                config = BrewlintConfig.defaults();
            }
            AnalysisEngine engine = AnalysisEngine.withDefaultRules(projectRoot, config, Brewlint.version());
            PrintWriter out = spec.commandLine().getOut();

            out.println();
            out.println("  " + Brewlint.NAME + " " + Brewlint.version() + " rules");
            out.println();
            for (io.github.brewlint.core.rule.Rule rule : engine.rules()) {
                String state = config.isEnabled(rule.id()) ? "" : "  (disabled)";
                out.printf("    %-9s %-16s %s%s%n",
                        rule.id(), rule.category(), rule.defaultSeverity(), state);
            }
            out.println();
            out.flush();
        }
    }

    /** Supplies {@code --version} output without a properties file lookup in the CLI. */
    public static final class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{Brewlint.NAME + " " + Brewlint.version()};
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new BrewlintCli()).execute(args));
    }
}
