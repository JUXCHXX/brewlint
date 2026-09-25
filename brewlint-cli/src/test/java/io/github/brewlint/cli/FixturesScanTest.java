package io.github.brewlint.cli;

import io.github.brewlint.core.config.BrewlintConfig;
import io.github.brewlint.core.config.ConfigLoader;
import io.github.brewlint.core.engine.AnalysisEngine;
import io.github.brewlint.core.engine.SourceCollector;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dogfooding test: runs the engine over this repository's own {@code fixtures/} and asserts the
 * findings the README promises.
 *
 * <p>This is the guardrail behind the claim "Brewlint finds real problems in real Spring code".
 * Unit tests prove the rules work on snippets; this proves the shipped configuration, the engine
 * wiring and the rule discovery all work together on a directory of files. It runs in
 * {@code ./mvnw test}, so a rule that stops firing breaks the build instead of quietly rotting.
 *
 * <p>It also asserts the negative case: the deliberately correct fixture must produce nothing. A
 * linter that only ever reports something is worthless.
 */
@DisplayName("fixtures/: the rules detect what the README claims")
class FixturesScanTest {

    private static AnalysisResult result;

    @BeforeAll
    static void scanFixtures() throws IOException {
        Path fixtures = locateFixturesDirectory();
        BrewlintConfig config = BrewlintConfig.defaults();
        AnalysisResult scan = AnalysisEngine
                .withDefaultRules(fixtures, config, "test")
                .analyze(SourceCollector.collectJavaFiles(fixtures));
        result = scan;
    }

    /** Walks up from the module directory until {@code fixtures/} is found. */
    private static Path locateFixturesDirectory() {
        Path candidate = Path.of("").toAbsolutePath();
        for (int depth = 0; depth < 6; depth++) {
            Path fixtures = candidate.resolve("fixtures");
            if (Files.isDirectory(fixtures)) {
                return fixtures;
            }
            candidate = candidate.getParent();
        }
        throw new IllegalStateException("Could not locate the fixtures directory from "
                + Path.of("").toAbsolutePath());
    }

    @Test
    @DisplayName("every fixture file parses, so a clean scan really means clean code")
    void fixturesParseCleanly() {
        assertThat(result.filesWithParseErrors())
                .as("fixtures must be syntactically valid Java; they are broken on purpose, not malformed")
                .isZero();
    }

    @Test
    @DisplayName("AOP001 finds every proxy-dependent annotation on a non-proxyable method")
    void findsAop001() {
        List<Finding> findings = rule("AOP001");

        // OrderService.java: chargeCard, auditStatic, sendReceipt, findById, evict,
        // nightlyReconciliation.
        assertThat(findings).hasSizeGreaterThanOrEqualTo(6);
        assertThat(findings).allSatisfy(finding ->
                assertThat(finding.filePath()).endsWith("OrderService.java"));
        assertThat(findings).anySatisfy(finding ->
                assertThat(finding.message()).contains("never scheduled"));
    }

    @Test
    @DisplayName("RES001 finds unclosed streams and JDBC objects")
    void findsRes001() {
        List<Finding> findings = rule("RES001");

        // ReportExporter: the field, the unclosed local, the var local.
        // InventoryDao: Connection, Statement, the unclosed BufferedReader.
        assertThat(findings).hasSizeGreaterThanOrEqualTo(6);
        assertThat(findings).extracting(Finding::filePath)
                .anyMatch(path -> path.endsWith("ReportExporter.java"))
                .anyMatch(path -> path.endsWith("InventoryDao.java"));
        assertThat(findings).anySatisfy(finding ->
                assertThat(finding.message()).contains("Connection"));
    }

    @Test
    @DisplayName("the deliberately correct fixture produces no findings at all")
    void correctFixtureIsClean() {
        Set<String> filesWithFindings = result.findings().stream()
                .map(Finding::filePath)
                .collect(Collectors.toSet());

        assertThat(filesWithFindings)
                .as("PaymentService.java is correct code and must stay clean")
                .noneMatch(path -> path.endsWith("PaymentService.java"));
    }

    @Test
    @DisplayName("correct usage inside the broken files is not reported either")
    void noFalsePositivesOnCorrectConstructs() {
        // exportCorrectly uses try-with-resources, exportWithManualClose uses try/finally, and
        // readPassedIn takes the stream as a parameter. None of the three is a leak.
        List<Finding> exporterFindings = result.findings().stream()
                .filter(finding -> finding.filePath().endsWith("ReportExporter.java"))
                .toList();

        assertThat(exporterFindings).hasSize(3);
        assertThat(exporterFindings).noneSatisfy(finding ->
                assertThat(finding.line()).isBetween(
                        lineOf("exportCorrectly"), lineOf("exportWithManualClose")));
    }

    @Test
    @DisplayName("findings point at plausible lines, not at the top of the file")
    void positionsAreMeaningful() {
        assertThat(result.findings()).allSatisfy(finding -> {
            assertThat(finding.line()).isGreaterThan(1);
            assertThat(finding.endLine()).isGreaterThanOrEqualTo(finding.line());
            assertThat(finding.column()).isGreaterThan(0);
        });
    }

    private static int lineOf(String methodName) {
        try {
            List<String> lines = Files.readAllLines(
                    locateFixturesDirectory().resolve("src/main/java/com/example/broken/ReportExporter.java"));
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(methodName + "(")) {
                    return index + 1;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        throw new IllegalStateException("Method not found in fixture: " + methodName);
    }

    private static List<Finding> rule(String ruleId) {
        return result.findings().stream()
                .filter(finding -> finding.ruleId().equals(ruleId))
                .toList();
    }
}
