package io.github.brewlint.cli;

import io.github.brewlint.ai.AiProvider;
import io.github.brewlint.ai.AiReviewService;
import io.github.brewlint.ai.anthropic.AnthropicProvider;
import io.github.brewlint.ai.ollama.OllamaProvider;
import io.github.brewlint.core.Brewlint;
import io.github.brewlint.core.config.BrewlintConfig;
import io.github.brewlint.core.config.ConfigLoader;
import io.github.brewlint.core.engine.AnalysisEngine;
import io.github.brewlint.core.engine.SourceCollector;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.report.JsonReportRenderer;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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

        @Option(names = "--format",
                paramLabel = "<format>",
                description = "Output format: terminal or json. Default: terminal.")
        String format = "terminal";

        @Option(names = {"-o", "--output"},
                paramLabel = "<file>",
                description = "Write the report to this file instead of standard output.")
        Path output;

        @Option(names = "--ai",
                paramLabel = "<provider>",
                description = "Run an extra review pass through a language model: anthropic (your "
                        + "API key, code leaves the machine) or ollama (local, nothing leaves). "
                        + "Never on by default, and the report is complete without it.")
        String aiProvider;

        @Option(names = "--ai-model",
                paramLabel = "<id>",
                description = "Model id for --ai. Check GET https://api.anthropic.com/v1/models, or "
                        + "run `ollama list`.")
        String aiModel;

        @Option(names = "--ai-max-findings",
                paramLabel = "<n>",
                description = "How many findings to ask the model about. Default: 50.")
        int aiMaxFindings = AiReviewService.DEFAULT_MAX_FINDINGS;

        @Option(names = "--ai-max-lines",
                paramLabel = "<n>",
                description = "How many lines of code to send. Default: 1200.")
        int aiMaxLines = AiReviewService.DEFAULT_MAX_EXCERPT_LINES;

        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        private final ReportRenderer terminalRenderer = new TerminalReportRenderer();
        private final ReportRenderer jsonRenderer = new JsonReportRenderer();

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

                // The AI pass is additive and optional. It runs after the deterministic scan and can
                // only add to the result, so a failure here cannot reduce what the rules found.
                if (aiProvider != null) {
                    result = withAi(result, files, projectRoot);
                }

                ReportOptions options = new ReportOptions(
                        AnsiSupport.isColorEnabled(noColor, forceColor),
                        maxFindings,
                        ReportOptions.DEFAULT_WIDTH);
                write(result, options);
                flush();

                return fails(result, failOn) ? EXIT_FINDINGS : EXIT_CLEAN;

            } catch (IOException | IllegalArgumentException exception) {
                spec.commandLine().getErr().println("brewlint: " + exception.getMessage());
                spec.commandLine().getErr().flush();
                return EXIT_ERROR;
            }
        }

        /**
         * Runs the optional AI pass and merges whatever it adds.
         *
         * <p>A provider that is missing, misconfigured or unreachable produces a warning and the
         * unchanged deterministic result. That is the whole contract of the feature: the report is
         * complete without it, and losing it must not be an error.
         *
         * <p>Every progress message goes to stderr. Writing them to stdout would corrupt
         * {@code --format json}, and a report that is syntactically invalid because of a status
         * line is worse than no status line: it breaks the consumer silently, and the consumer is
         * the VS Code extension or a CI job that has no way to tell a truncated report from a
         * broken one.
         */
        private AnalysisResult withAi(
                AnalysisResult result, List<Path> files, Path projectRoot) {

            AiProvider provider = providerFor(aiProvider, aiModel);
            PrintWriter progress = spec.commandLine().getErr();

            if (provider == null || !provider.isConfigured()) {
                progress.println("brewlint: AI pass skipped: "
                        + (provider == null
                                ? "unknown provider '" + aiProvider + "'. Expected: anthropic, ollama."
                                : provider.name() + " is not configured.")
                        + " The report is the complete set of rule findings.");
                progress.flush();
                return result;
            }

            progress.print("brewlint: asking " + provider.name() + " (" + provider.model() + ") ... ");
            progress.flush();

            AiReviewService.Outcome outcome = AiReviewService.review(
                    provider, result, files, projectRoot, aiMaxFindings, aiMaxLines);

            if (outcome.failed()) {
                progress.println("skipped");
                progress.println("brewlint: AI pass failed: " + outcome.failureReason());
                progress.println("brewlint: the report is the complete set of rule findings.");
                progress.flush();
                return result;
            }

            progress.println(outcome.additionalFindings().size() + " suggestion(s), "
                    + outcome.verdicts().size() + " verdict(s)");
            progress.flush();

            if (outcome.additionalFindings().isEmpty() && outcome.verdicts().isEmpty()) {
                return result;
            }

            List<io.github.brewlint.core.model.Finding> merged = new ArrayList<>(result.findings());
            merged.addAll(outcome.additionalFindings());
            merged.sort(null);

            return new io.github.brewlint.core.model.AnalysisResult(
                    List.copyOf(merged),
                    result.filesScanned(),
                    result.filesWithParseErrors(),
                    result.duration(),
                    result.toolVersion());
        }

        /** Builds the requested provider, or null for an unknown name. */
        private AiProvider providerFor(String name, String model) {
            return switch (name.trim().toLowerCase(Locale.ROOT)) {
                case "anthropic" -> AnthropicProvider.builder().model(model).build();
                case "ollama" -> OllamaProvider.builder().model(model).build();
                default -> null;
            };
        }

        private void write(AnalysisResult result, ReportOptions options) throws IOException {
            ReportRenderer renderer = rendererFor(format);
            // JSON is never truncated. A client that reads a partial findings array has to
            // distinguish "there were no more findings" from "output was cut off", which is exactly
            // the ambiguity that makes a machine-readable format untrustworthy.
            ReportOptions effective = "json".equals(renderer.format())
                    ? options.withMaxFindings(null)
                    : options;

            if (output == null) {
                renderer.render(result, effective, spec.commandLine().getOut());
                return;
            }
            Path parent = output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (java.io.Writer writer = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
                renderer.render(result, effective, writer);
            }
        }

        private ReportRenderer rendererFor(String requested) {
            return switch (requested.toLowerCase(java.util.Locale.ROOT)) {
                case "terminal" -> terminalRenderer;
                case "json" -> jsonRenderer;
                default -> throw new IllegalArgumentException(
                        "Unknown format '" + requested + "'. Expected: terminal, json");
            };
        }

        private void flush() {
            spec.commandLine().getOut().flush();
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
