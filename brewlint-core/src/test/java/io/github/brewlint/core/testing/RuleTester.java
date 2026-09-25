package io.github.brewlint.core.testing;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.project.ProjectIndex;
import io.github.brewlint.core.project.ProjectIndexBuilder;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;
import io.github.brewlint.core.type.SyntacticTypeSolver;
import io.github.brewlint.core.type.TypeSolver;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs a single rule against source code held in a string.
 *
 * <p>Rule tests use inline strings rather than files in {@code fixtures/}. That keeps each test next
 * to the rule it covers, makes a failing case readable in the test name, and runs in milliseconds
 * with no filesystem and no compiler. {@code fixtures/} is for end-to-end runs and the demo, not
 * for unit tests.
 *
 * <p>JavaParser parses a string with no setup, which is what makes this practical.
 */
public final class RuleTester {

    private static final Path VIRTUAL_FILE = Path.of("Test.java");
    private static final String VIRTUAL_PATH = "Test.java";
    private static final ProjectIndex NO_PROJECT = null;

    private RuleTester() {
    }

    /** Analyses a complete source file. */
    public static List<Finding> check(Rule rule, String source) {
        return run(rule, parseOrFail(source), NO_PROJECT);
    }

    /**
     * Analyses a class body, so a test can focus on the member under test:
     * {@code checkInClass(rule, "void m(InputStream in) throws IOException {}")}
     */
    public static List<Finding> checkInClass(Rule rule, String classBody) {
        return check(rule, "class Test {\n" + classBody + "\n}\n");
    }

    /** Analyses an interface body, for rules that care about the declaration kind. */
    public static List<Finding> checkInInterface(Rule rule, String body) {
        return check(rule, "interface Test {\n" + body + "\n}\n");
    }

    /**
     * Analyses the first source with a project index built from all of them.
     *
     * <p>For rules that compare across files, such as prototype-scoped injection. Each source is a
     * separate file, and the first one is the file under test.
     */
    public static List<Finding> checkWithProject(Rule rule, String... projectSources) {
        assertThat(projectSources).as("at least the file under test is required").isNotEmpty();
        return run(
                rule,
                parseOrFail(projectSources[0]),
                ProjectIndexBuilder.buildFromSources(List.of(projectSources)));
    }

    private static CompilationUnit parseOrFail(String source) {
        ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE))
                .parse(source);
        assertThat(parsed.getResult())
                .as("test source must parse, otherwise the test proves nothing: %s", parsed.getProblems())
                .isPresent();
        return parsed.getResult().orElseThrow();
    }

    private static List<Finding> run(Rule rule, CompilationUnit compilationUnit, ProjectIndex index) {
        List<Finding> findings = new ArrayList<>();
        RuleContext context = new StringRuleContext(compilationUnit, new SyntacticTypeSolver(), index);
        rule.analyze(context, new CapturingCollector(rule, findings));
        return findings;
    }

    private record StringRuleContext(
            CompilationUnit compilationUnit,
            TypeSolver typeSolver,
            ProjectIndex projectIndex) implements RuleContext {

        @Override
        public CompilationUnit compilationUnit() {
            return compilationUnit;
        }

        @Override
        public Path file() {
            return VIRTUAL_FILE;
        }

        @Override
        public String relativePath() {
            return VIRTUAL_PATH;
        }

        @Override
        public TypeSolver typeSolver() {
            return typeSolver;
        }

        @Override
        public ProjectIndex projectIndex() {
            return projectIndex;
        }
    }

    private record CapturingCollector(Rule rule, List<Finding> sink) implements RuleCollector {

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
                    rule.id(), rule.category(), rule.defaultSeverity(), VIRTUAL_PATH,
                    line, column, endLine, message, suggestion));
        }
    }
}
