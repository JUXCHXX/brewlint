package io.github.brewlint.cli;

import io.github.brewlint.core.config.BrewlintConfig;
import io.github.brewlint.core.config.ConfigLoader;
import io.github.brewlint.core.engine.AnalysisEngine;
import io.github.brewlint.core.engine.SourceCollector;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
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
    @DisplayName("every rule that ships finds something in the broken fixtures")
    void everyShippedRuleFires() {
        // A rule that never fires anywhere is either broken or pointless, and nothing in the build
        // would say so without this.
        assertThat(ruleIds())
                .containsExactlyInAnyOrder("AOP001", "BEAN001", "BEAN002", "BEAN003",
                        "PERF001", "RES001", "RES002", "TX002", "TX003");
    }

    @Test
    @DisplayName("PERF001 finds the query in the loop, in each of the three loop forms")
    void findsPerf001() {
        List<Finding> findings = rule("PERF001");

        // CustomerReportService: a for loop, a stream forEach, and a while loop. Three forms,
        // because a rule that only understands statement-position loops misses every codebase that
        // has read about streams, and nothing else in the build would notice.
        assertThat(findings).hasSize(3);
        assertThat(findings).allSatisfy(finding ->
                assertThat(finding.filePath()).endsWith("CustomerReportService.java"));
        assertThat(findings).anySatisfy(finding ->
                assertThat(finding.message()).contains("findById()"));
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
    @DisplayName("TX002 finds self-invoked transactions and nothing else")
    void findsTx002() {
        List<Finding> findings = rule("TX002");

        // PaymentProcessor: ledger() called from capture() and from settle().
        assertThat(findings).hasSizeGreaterThanOrEqualTo(2);
        assertThat(findings).allSatisfy(finding -> {
            assertThat(finding.filePath()).endsWith("PaymentProcessor.java");
            assertThat(finding.message()).contains("bypasses the Spring proxy");
        });
    }

    @Test
    @DisplayName("TX003 finds checked exceptions without rollbackFor")
    void findsTx003() {
        List<Finding> findings = rule("TX003");

        // PaymentProcessor: persist() declares SQLException, refresh() catches it.
        assertThat(findings).hasSizeGreaterThanOrEqualTo(2);
        assertThat(findings).allSatisfy(finding ->
                assertThat(finding.filePath()).endsWith("PaymentProcessor.java"));
        assertThat(findings).allSatisfy(finding ->
                assertThat(finding.suggestion()).contains("rollbackFor"));
    }

    @Test
    @DisplayName("RES001 finds unclosed streams and JDBC objects")
    void findsRes001() {
        List<Finding> findings = rule("RES001");

        assertThat(findings).hasSizeGreaterThanOrEqualTo(6);
        assertThat(findings).extracting(Finding::filePath)
                .anyMatch(path -> path.endsWith("ReportExporter.java"))
                .anyMatch(path -> path.endsWith("InventoryDao.java"));
        assertThat(findings).anySatisfy(finding ->
                assertThat(finding.message()).contains("Connection"));
    }

    @Test
    @DisplayName("RES002 finds unclosed Files streams")
    void findsRes002() {
        List<Finding> findings = rule("RES002");

        // LogImporter: countLines, countEntries, entries, forEachLine.
        assertThat(findings).hasSizeGreaterThanOrEqualTo(4);
        assertThat(findings).allSatisfy(finding ->
                assertThat(finding.filePath()).endsWith("LogImporter.java"));
    }

    @Test
    @DisplayName("BEAN001 finds an entity that is also a component")
    void findsBean001() {
        List<Finding> findings = rule("BEAN001");

        assertThat(findings).hasSizeGreaterThanOrEqualTo(1);
        assertThat(findings).anySatisfy(finding ->
                assertThat(finding.message()).contains("@Entity").contains("@Component"));
    }

    @Test
    @DisplayName("BEAN002 finds a prototype bean held by a singleton")
    void findsBean002() {
        List<Finding> findings = rule("BEAN002");

        assertThat(findings).hasSizeGreaterThanOrEqualTo(1);
        assertThat(findings).anySatisfy(finding ->
                assertThat(finding.message()).contains("ReportCache").contains("prototype"));
    }

    @Test
    @DisplayName("BEAN003 finds field injection")
    void findsBean003() {
        List<Finding> findings = rule("BEAN003");

        assertThat(findings).hasSizeGreaterThanOrEqualTo(1);
        assertThat(findings).allSatisfy(finding ->
                assertThat(finding.severity())
                        .as("a convention, not a defect")
                        .isEqualTo(Severity.INFO));
    }

    @Test
    @DisplayName("the deliberately correct fixtures produce no findings at all")
    void correctFixturesAreClean() {
        Set<String> filesWithFindings = result.findings().stream()
                .map(Finding::filePath)
                .collect(Collectors.toSet());

        assertThat(filesWithFindings)
                .as("the com/example/correct package is correct code and must stay clean")
                .allMatch(path -> !path.contains("com/example/correct"));
    }

    @Test
    @DisplayName("correct usage inside the broken files is not reported either")
    void noFalsePositivesOnCorrectConstructs() {
        // ReportExporter.exportCorrectly uses try-with-resources, exportWithManualClose uses
        // try/finally, and readPassedIn takes the stream as a parameter. None is a leak.
        List<Finding> exporterFindings = result.findings().stream()
                .filter(finding -> finding.filePath().endsWith("ReportExporter.java"))
                .toList();

        assertThat(exporterFindings).hasSize(3);
        assertThat(exporterFindings).noneSatisfy(finding ->
                assertThat(finding.line()).isBetween(
                        lineOf("ReportExporter.java", "exportCorrectly"),
                        lineOf("ReportExporter.java", "exportWithManualClose")));
    }

    @Test
    @DisplayName("a transaction with rollbackFor is never reported by TX003")
    void noTx003WhereRollbackForPresent() {
        // PaymentProcessor.archive and .purge both name what to roll back on.
        assertThat(result.findings())
                .filteredOn(finding -> finding.ruleId().equals("TX003"))
                .noneSatisfy(finding -> assertThat(finding.line()).isBetween(
                        lineOf("PaymentProcessor.java", "public void archive"),
                        lineOf("PaymentProcessor.java", "public void purge")));
    }

    @Test
    @DisplayName("a Files stream in try-with-resources is never reported by RES002")
    void noRes002WhereTryWithResources() {
        assertThat(result.findings())
                .filteredOn(finding -> finding.ruleId().equals("RES002"))
                .noneSatisfy(finding -> assertThat(finding.line()).isBetween(
                        lineOf("LogImporter.java", "public long countCorrectly"),
                        lineOf("LogImporter.java", "public List<String> inMemory")));
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

    private static Set<String> ruleIds() {
        return result.findings().stream().map(Finding::ruleId).collect(Collectors.toSet());
    }

    private static int lineOf(String fileName, String marker) {
        try {
            List<String> lines = Files.readAllLines(
                    locateFixturesDirectory().resolve("src/main/java/com/example/broken/" + fileName));
            for (int index = 0; index < lines.size(); index++) {
                if (lines.get(index).contains(marker)) {
                    return index + 1;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        throw new IllegalStateException("Marker not found in fixture " + fileName + ": " + marker);
    }

    private static List<Finding> rule(String ruleId) {
        return result.findings().stream()
                .filter(finding -> finding.ruleId().equals(ruleId))
                .toList();
    }
}
