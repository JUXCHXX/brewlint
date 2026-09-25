package io.github.brewlint.core.engine;

import io.github.brewlint.core.config.BrewlintConfig;
import io.github.brewlint.core.config.RuleSettings;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;
import io.github.brewlint.core.rules.aop.AopAnnotationOnNonProxyableMethodRule;
import io.github.brewlint.core.rules.resource.UnclosedResourceRule;
import io.github.brewlint.core.type.SyntacticTypeSolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AnalysisEngine")
class AnalysisEngineTest {

    private static final List<Rule> RULES =
            List.of(new AopAnnotationOnNonProxyableMethodRule(), new UnclosedResourceRule());

    @TempDir
    Path projectRoot;

    @Test
    @DisplayName("finds problems and reports them relative to the project root")
    void reportsFindingsWithRelativePaths() throws IOException {
        write("src/main/java/com/example/OrderService.java", """
                package com.example;
                import java.io.InputStream;
                class OrderService {
                    @Transactional
                    private void charge() {}
                    void read() throws Exception {
                        InputStream in = new FileInputStream("a.txt");
                        in.read();
                    }
                }
                """);

        AnalysisResult result = engine(BrewlintConfig.defaults()).analyze(SourceCollector.collectJavaFiles(projectRoot));

        assertThat(result.filesScanned()).isEqualTo(1);
        assertThat(result.filesWithParseErrors()).isZero();
        assertThat(result.findings()).extracting(Finding::ruleId)
                .containsExactlyInAnyOrder("AOP001", "RES001");
        assertThat(result.findings()).allSatisfy(finding ->
                assertThat(finding.filePath()).isEqualTo("src/main/java/com/example/OrderService.java"));
        assertThat(result.findings()).allSatisfy(finding ->
                assertThat(finding.line()).isGreaterThanOrEqualTo(1));
    }

    @Test
    @DisplayName("orders findings by descending severity, then by location")
    void sortsFindings() throws IOException {
        write("A.java", """
                class A {
                    @Transactional
                    private void a() {}
                    void b() throws Exception {
                        InputStream in = new FileInputStream("a.txt");
                        in.read();
                    }
                }
                """);

        AnalysisResult result = engine(BrewlintConfig.defaults()).analyze(List.of(projectRoot.resolve("A.java")));

        assertThat(result.findings()).extracting(Finding::ruleId).containsExactly("AOP001", "RES001");
    }

    @Test
    @DisplayName("skips files matching an exclude pattern")
    void appliesExcludePatterns() throws IOException {
        write("src/main/java/com/example/Generated.java", """
                class Generated {
                    @Transactional
                    private void a() {}
                }
                """);
        write("src/main/java/com/example/Real.java", """
                class Real {
                    @Transactional
                    private void a() {}
                }
                """);

        BrewlintConfig config = new BrewlintConfig(Map.of(), List.of("**/generated/**", "**/Generated.java"));
        AnalysisResult result = engine(config).analyze(SourceCollector.collectJavaFiles(projectRoot));

        assertThat(result.filesScanned()).isEqualTo(1);
        assertThat(result.findings()).hasSize(1);
    }

    @Test
    @DisplayName("a rule disabled in brewlint.yml reports nothing")
    void honoursDisabledRules() throws IOException {
        write("A.java", """
                class A {
                    @Transactional
                    private void a() {}
                }
                """);

        BrewlintConfig config = new BrewlintConfig(Map.of("AOP001", RuleSettings.off()), List.of());
        AnalysisResult result = engine(config).analyze(List.of(projectRoot.resolve("A.java")));

        assertThat(result.findings()).isEmpty();
        assertThat(result.filesScanned()).isEqualTo(1);
    }

    @Test
    @DisplayName("a severity in brewlint.yml overrides the rule's default")
    void honoursSeverityOverride() throws IOException {
        write("A.java", """
                class A {
                    @Transactional
                    private void a() {}
                }
                """);

        BrewlintConfig config = new BrewlintConfig(
                Map.of("AOP001", RuleSettings.withSeverity(Severity.INFO)), List.of());
        AnalysisResult result = engine(config).analyze(List.of(projectRoot.resolve("A.java")));

        assertThat(result.findings()).singleElement()
                .extracting(Finding::severity).isEqualTo(Severity.INFO);
    }

    @Test
    @DisplayName("a file with syntax errors is still scanned, and still counted as broken")
    void survivesUnparseableFiles() throws IOException {
        write("Broken.java", "class Broken { this is not java");
        write("Fine.java", """
                class Fine {
                    @Transactional
                    private void a() {}
                }
                """);

        AnalysisResult result = engine(BrewlintConfig.defaults())
                .analyze(SourceCollector.collectJavaFiles(projectRoot));

        // JavaParser recovers and returns a usable AST, so both files are analysed and only the
        // broken one is counted as having problems.
        assertThat(result.filesScanned()).isEqualTo(2);
        assertThat(result.filesWithParseErrors()).isEqualTo(1);
        assertThat(result.findings()).extracting(Finding::ruleId).containsExactly("AOP001");
        assertThat(result.findings()).singleElement()
                .extracting(Finding::filePath).isEqualTo("Fine.java");
    }

    @Test
    @DisplayName("one broken rule does not cost the user the other rules' findings")
    void survivesARuleThatThrows() throws IOException {
        write("A.java", """
                class A {
                    @Transactional
                    private void a() {}
                }
                """);

        Rule exploding = new Rule() {
            @Override
            public String id() {
                return "BOOM";
            }

            @Override
            public String category() {
                return "test";
            }

            @Override
            public Severity defaultSeverity() {
                return Severity.ERROR;
            }

            @Override
            public void analyze(RuleContext context, RuleCollector out) {
                throw new IllegalStateException("boom");
            }
        };

        AnalysisResult result = new AnalysisEngine(
                projectRoot,
                List.of(exploding, new AopAnnotationOnNonProxyableMethodRule()),
                new SyntacticTypeSolver(),
                BrewlintConfig.defaults(),
                "test").analyze(List.of(projectRoot.resolve("A.java")));

        assertThat(result.findings()).extracting(Finding::ruleId).containsExactly("AOP001");
    }

    @Test
    @DisplayName("a rule id in brewlint.yml that no rule provides is rejected")
    void rejectsUnknownRuleIds() {
        assertThatThrownBy(() -> new AnalysisEngine(
                projectRoot,
                RULES,
                new SyntacticTypeSolver(),
                new BrewlintConfig(Map.of("NOPE001", RuleSettings.off()), List.of()),
                "test"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOPE001")
                .hasMessageContaining("AOP001");
    }

    @Test
    @DisplayName("withDefaultRules discovers both shipped rules through ServiceLoader")
    void defaultRulesAreDiscovered() throws IOException {
        write("A.java", """
                class A {
                    @Transactional
                    private void a() {}
                    void b() throws Exception {
                        InputStream in = new FileInputStream("a.txt");
                        in.read();
                    }
                }
                """);

        AnalysisResult result = AnalysisEngine
                .withDefaultRules(projectRoot, BrewlintConfig.defaults(), "test")
                .analyze(SourceCollector.collectJavaFiles(projectRoot));

        assertThat(result.findings()).extracting(Finding::ruleId)
                .containsExactlyInAnyOrder("AOP001", "RES001");
    }

    private AnalysisEngine engine(BrewlintConfig config) {
        return new AnalysisEngine(projectRoot, RULES, new SyntacticTypeSolver(), config, "test");
    }

    private void write(String relativePath, String content) throws IOException {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
