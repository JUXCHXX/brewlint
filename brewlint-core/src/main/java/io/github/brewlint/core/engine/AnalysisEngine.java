package io.github.brewlint.core.engine;

import com.github.javaparser.JavaParser;
import com.github.javaparser.JavaToken;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.TokenRange;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import io.github.brewlint.core.config.BrewlintConfig;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.project.ProjectIndex;
import io.github.brewlint.core.project.ProjectIndexBuilder;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;
import io.github.brewlint.core.type.TypeSolver;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.logging.Logger;

/**
 * Runs a set of rules over a set of files.
 *
 * <p>Knows nothing about terminals, JSON or PDFs. It takes paths in and returns an
 * {@link AnalysisResult}; every output format is built on top of that single result object.
 *
 * <h2>Failure policy</h2>
 * A file that will not parse is counted and reported, and the scan continues. A rule that throws is
 * logged and disabled for the rest of the run. Neither aborts the scan: a linter that stops at the
 * first odd file is useless on a real codebase, where not everything compiles.
 */
public final class AnalysisEngine {

    private static final Logger LOG = Logger.getLogger(AnalysisEngine.class.getName());

    private final List<Rule> rules;
    private final TypeSolver typeSolver;
    private final BrewlintConfig config;
    private final List<GlobPattern> excludePatterns;
    private final Path projectRoot;
    private final String toolVersion;
    private final JavaParser parser;

    public AnalysisEngine(
            Path projectRoot,
            List<Rule> rules,
            TypeSolver typeSolver,
            BrewlintConfig config,
            String toolVersion) {
        this(projectRoot, rules, typeSolver, config, toolVersion, new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setCharacterEncoding(java.nio.charset.StandardCharsets.UTF_8)));
    }

    public AnalysisEngine(
            Path projectRoot,
            List<Rule> rules,
            TypeSolver typeSolver,
            BrewlintConfig config,
            String toolVersion,
            JavaParser parser) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.rules = RuleRegistry.validate(rules);
        this.typeSolver = typeSolver;
        this.config = config;
        this.excludePatterns = config.exclude().stream().map(GlobPattern::compile).toList();
        this.toolVersion = toolVersion;
        this.parser = parser;
        config.validateAgainst(this.rules);
    }

    /** Builds an engine with every rule on the classpath and syntactic type resolution. */
    public static AnalysisEngine withDefaultRules(Path projectRoot, BrewlintConfig config, String toolVersion) {
        return new AnalysisEngine(
                projectRoot,
                RuleRegistry.discover(),
                new io.github.brewlint.core.type.SyntacticTypeSolver(),
                config,
                toolVersion);
    }

    public List<Rule> rules() {
        return rules;
    }

    public AnalysisResult analyze(Collection<Path> files) {
        long startNanos = System.nanoTime();
        List<Finding> findings = new ArrayList<>();
        int filesScanned = 0;
        int filesWithParseErrors = 0;

        // Built only when an enabled rule asks for it, because building it means parsing every file
        // a second time. A run of single-file rules pays nothing.
        List<Path> analysable = files.stream()
                .filter(file -> !isExcluded(relativePathOf(file)))
                .toList();
        ProjectIndex projectIndex = ProjectIndexBuilder.isNeededFor(rules, config::isEnabled)
                ? ProjectIndexBuilder.build(analysable)
                : ProjectIndex.EMPTY;

        for (Path file : files) {
            String relativePath = relativePathOf(file);
            if (isExcluded(relativePath)) {
                continue;
            }
            ParseResult<CompilationUnit> parsed;
            try {
                parsed = parser.parse(file);
            } catch (IOException | RuntimeException exception) {
                // JavaParser wraps read failures in an unsuccessful result, but a malformed path or
                // an encoding problem can escape. Neither should abort the scan.
                filesWithParseErrors++;
                LOG.warning("Could not read " + relativePath + ": " + exception);
                continue;
            }
            if (parsed.getResult().isEmpty()) {
                filesWithParseErrors++;
                LOG.warning("Could not parse " + relativePath + ": " + describeFailure(parsed));
                continue;
            }
            // JavaParser recovers from syntax errors and still returns a usable AST, so a file with
            // problems is still worth scanning: the parts that did parse may hold real findings.
            if (!parsed.isSuccessful()) {
                filesWithParseErrors++;
                LOG.warning("Parsed with problems " + relativePath + ": " + describeFailure(parsed));
            }
            filesScanned++;

            CompilationUnit compilationUnit = parsed.getResult().orElseThrow();
            RuleContext context = new DefaultRuleContext(
                    compilationUnit, file, relativePath, typeSolver, projectIndex);
            for (Rule rule : rules) {
                if (!config.isEnabled(rule.id())) {
                    continue;
                }
                try {
                    rule.analyze(context, new CollectingRuleCollector(
                            rule, relativePath, config.severityFor(rule), findings));
                } catch (RuntimeException exception) {
                    // One broken rule must not cost the user every other rule's findings.
                    LOG.warning("Rule " + rule.id() + " failed on " + relativePath
                            + " and was skipped for the rest of this run: " + exception);
                }
            }
        }

        findings.sort(null);
        Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
        return new AnalysisResult(
                List.copyOf(findings), filesScanned, filesWithParseErrors, duration, toolVersion);
    }

    private String describeFailure(ParseResult<CompilationUnit> parsed) {
        return parsed.getProblems().stream()
                .map(problem -> problem.getMessage()
                        + problem.getLocation()
                                .map(TokenRange::getBegin)
                                .flatMap(JavaToken::getRange)
                                .map(range -> " (line " + range.begin.line + ")")
                                .orElse(""))
                .findFirst()
                .orElse("unknown parse error");
    }

    private boolean isExcluded(String relativePath) {
        return excludePatterns.stream().anyMatch(pattern -> pattern.matches(relativePath));
    }

    /** Project-relative, forward slashes, so output is identical on every platform. */
    private String relativePathOf(Path file) {
        Path normalised = file.toAbsolutePath().normalize();
        if (normalised.startsWith(projectRoot)) {
            return projectRoot.relativize(normalised).toString().replace('\\', '/');
        }
        return normalised.toString().replace('\\', '/');
    }

    private record DefaultRuleContext(
            CompilationUnit compilationUnit,
            Path file,
            String relativePath,
            TypeSolver typeSolver,
            ProjectIndex projectIndex) implements RuleContext {
    }

    /**
     * Stamps the rule's identity and configured severity onto everything the rule reports, which is
     * why rules never construct a {@link Finding} themselves.
     */
    private record CollectingRuleCollector(
            Rule rule,
            String relativePath,
            Severity severity,
            List<Finding> sink) implements RuleCollector {

        @Override
        public void report(Node node, String message, String suggestion) {
            int line = node.getRange().map(range -> range.begin.line).orElse(1);
            int column = node.getRange().map(range -> range.begin.column).orElse(1);
            int endLine = node.getRange().map(range -> range.end.line).orElse(line);
            reportAt(line, column, endLine, message, suggestion);
        }

        @Override
        public void reportAt(int line, int column, int endLine, String message, String suggestion) {
            sink.add(new Finding(
                    rule.id(),
                    rule.category(),
                    severity,
                    relativePath,
                    Math.max(1, line),
                    Math.max(1, column),
                    Math.max(Math.max(1, line), endLine),
                    message,
                    suggestion));
        }
    }
}
