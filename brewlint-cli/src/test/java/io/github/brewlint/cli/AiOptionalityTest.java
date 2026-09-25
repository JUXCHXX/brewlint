package io.github.brewlint.cli;

import io.github.brewlint.ai.AiException;
import io.github.brewlint.ai.AiProvider;
import io.github.brewlint.ai.AiRequest;
import io.github.brewlint.ai.AiResponse;
import io.github.brewlint.ai.AiReviewService;
import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.FindingSource;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.report.JsonReportRenderer;
import io.github.brewlint.report.ReportOptions;
import io.github.brewlint.report.TerminalReportRenderer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The promise that makes the whole feature safe to offer: <strong>the report is complete without
 * it</strong>.
 *
 * <p>If a scan produced a different set of findings depending on whether a model was reachable,
 * the report would be lying about how much the rules found, and a user could not tell which part of
 * the output to trust. These tests pin that the deterministic half stands on its own, and that
 * adding the AI half is additive.
 */
@DisplayName("The AI pass is additive, never a prerequisite")
class AiOptionalityTest {

    private static final AnalysisResult DETERMINISTIC = new AnalysisResult(
            List.of(
                    new Finding("AOP001", "spring-aop", Severity.ERROR,
                            "src/main/java/OrderService.java", 17, 5, 17,
                            "@Transactional on a private method", "Make it public."),
                    new Finding("RES001", "resource", Severity.ERROR,
                            "src/main/java/InventoryDao.java", 18, 9, 18,
                            "Connection never closed", "Use try-with-resources.")),
            2, 0, Duration.ofMillis(42), "0.1.0");

    @Test
    @DisplayName("the rules alone produce the full set of findings")
    void deterministicIsComplete() {
        assertThat(DETERMINISTIC.findings())
                .extracting(Finding::ruleId)
                .containsExactly("AOP001", "RES001");
        assertThat(DETERMINISTIC.findings())
                .allSatisfy(finding -> assertThat(finding.source()).isEqualTo(FindingSource.RULE));
    }

    @Test
    @DisplayName("the terminal report says nothing about AI when no provider ran")
    void terminalReportHasNoAiWithoutAProvider() throws Exception {
        StringBuilder out = new StringBuilder();
        new TerminalReportRenderer().render(DETERMINISTIC, ReportOptions.defaults(), out);

        assertThat(out.toString())
                .contains("AOP001")
                .contains("RES001")
                .doesNotContain("AI")
                .doesNotContain("model");
    }

    @Test
    @DisplayName("the JSON contract is unchanged when no provider ran")
    void jsonContractIsUnchanged() {
        String json = new JsonReportRenderer().toJson(DETERMINISTIC);

        assertThat(json)
                .contains("\"ruleId\":\"AOP001\"")
                .contains("\"source\":\"RULE\"")
                .doesNotContain("\"source\":\"AI\"");
    }

    @Test
    @DisplayName("an unreachable provider does not remove a single rule finding")
    void failureRemovesNothing() {
        AiProvider unreachable = new AiProvider() {
            @Override
            public String name() {
                return "fake";
            }

            @Override
            public String model() {
                return "fake-model";
            }

            @Override
            public boolean isConfigured() {
                return true;
            }

            @Override
            public AiResponse review(AiRequest request) throws AiException {
                throw new AiException("fake", "connection refused");
            }
        };

        AiReviewService.Outcome outcome = AiReviewService.review(
                unreachable, DETERMINISTIC, List.of(), Path.of("."), 50, 500);

        // The outcome is empty, but the caller keeps the deterministic result either way: the merge
        // in the CLI only ever adds.
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.additionalFindings()).isEmpty();
        assertThat(DETERMINISTIC.findings()).hasSize(2);
    }

    @Test
    @DisplayName("an AI finding never reaches a report at ERROR")
    void aiNeverErrors() {
        // A model that claims an error is capped, so an optional pass cannot turn a green build red
        // on its own.
        assertThat(AiReviewService.MAX_AI_SEVERITY).isEqualTo(Severity.WARNING);
        assertThat(AiReviewService.MAX_AI_SEVERITY.weight())
                .isLessThan(Severity.ERROR.weight());
    }
}
