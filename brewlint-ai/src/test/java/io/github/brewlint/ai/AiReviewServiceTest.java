package io.github.brewlint.ai;

import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.FindingSource;
import io.github.brewlint.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The trust boundary. These tests are the argument that an AI suggestion is never allowed to look
 * like a proven finding.
 */
@DisplayName("AiReviewService: an AI suggestion is never mistaken for a rule finding")
class AiReviewServiceTest {

    @TempDir
    Path projectRoot;

    private AnalysisResult deterministic() {
        return new AnalysisResult(
                List.of(new Finding("AOP001", "spring-aop", Severity.ERROR,
                        "src/main/java/OrderService.java", 17, 5, 17,
                        "@Transactional on a private method", "Make it public.")),
                1, 0, Duration.ofMillis(10), "0.1.0");
    }

    /** A provider that answers whatever it is given, and records what it was asked. */
    private record FakeProvider(AiResponse answer, AiException failure, List<AiRequest> seen)
            implements AiProvider {

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
            seen.add(request);
            if (failure != null) {
                throw failure;
            }
            return answer;
        }
    }

    private AiResponse answerWith(String... suggestions) {
        List<AiResponse.AiSuggestion> parsed = new java.util.ArrayList<>();
        for (String suggestion : suggestions) {
            String[] parts = suggestion.split("\\|", -1);
            parsed.add(new AiResponse.AiSuggestion(parts[0], Integer.parseInt(parts[1]),
                    parts[2], parts[3], parts[4]));
        }
        return new AiResponse("fake", "fake-model", List.of(), parsed);
    }

    private AiReviewService.Outcome run(AiProvider provider) throws IOException {
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Path source = projectRoot.resolve("src/main/java/OrderService.java");
        Files.writeString(source, "class OrderService {}");
        return AiReviewService.review(provider, deterministic(), List.of(source), projectRoot, 50, 500);
    }

    @Nested
    @DisplayName("severity")
    class Severities {

        @Test
        @DisplayName("an ERROR from a model is capped at WARNING")
        void capsError() throws IOException {
            AiProvider provider = new FakeProvider(
                    answerWith("src/main/java/OrderService.java|30|ERROR|a|b"),
                    null, new java.util.ArrayList<>());

            AiReviewService.Outcome outcome = run(provider);

            assertThat(outcome.additionalFindings()).singleElement()
                    .extracting(Finding::severity).isEqualTo(Severity.WARNING);
        }

        @Test
        @DisplayName("INFO and WARNING pass through unchanged")
        void lowerSeveritiesPassThrough() throws IOException {
            AiProvider provider = new FakeProvider(
                    answerWith(
                            "src/main/java/OrderService.java|30|WARNING|a|b",
                            "src/main/java/OrderService.java|31|INFO|c|d"),
                    null, new java.util.ArrayList<>());

            assertThat(run(provider).additionalFindings())
                    .extracting(Finding::severity)
                    .containsExactly(Severity.WARNING, Severity.INFO);
        }

        @Test
        @DisplayName("a nonsense severity becomes INFO")
        void nonsenseSeverity() {
            assertThat(AiReviewService.cap("CRITICAL")).isEqualTo(Severity.INFO);
            assertThat(AiReviewService.cap("")).isEqualTo(Severity.INFO);
        }

        @Test
        @DisplayName("the cap is a warning, so an AI pass can never fail a build on its own")
        void neverExceedsWarning() {
            for (String severity : List.of("ERROR", "WARNING", "INFO")) {
                assertThat(AiReviewService.cap(severity).weight())
                        .isLessThanOrEqualTo(AiReviewService.MAX_AI_SEVERITY.weight());
            }
        }
    }

    @Nested
    @DisplayName("provenance")
    class Provenance {

        @Test
        @DisplayName("every AI finding says it came from a model")
        void sourceIsMarked() throws IOException {
            AiProvider provider = new FakeProvider(
                    answerWith("src/main/java/OrderService.java|30|WARNING|a|b"),
                    null, new java.util.ArrayList<>());

            assertThat(run(provider).additionalFindings()).singleElement()
                    .extracting(Finding::source).isEqualTo(FindingSource.AI);
        }

        @Test
        @DisplayName("a rule finding stays marked as a rule finding")
        void ruleFindingsUnaffected() {
            assertThat(deterministic().findings()).singleElement()
                    .extracting(Finding::source).isEqualTo(FindingSource.RULE);
        }
    }

    @Nested
    @DisplayName("hallucinations")
    class Hallucinations {

        @Test
        @DisplayName("a suggestion for a file that was never scanned is dropped")
        void unknownFileDropped() throws IOException {
            AiProvider provider = new FakeProvider(
                    answerWith("src/main/java/DoesNotExist.java|30|WARNING|a|b"),
                    null, new java.util.ArrayList<>());

            assertThat(run(provider).additionalFindings()).isEmpty();
        }

        @Test
        @DisplayName("a suggestion for a file that does exist is kept")
        void knownFileKept() throws IOException {
            AiProvider provider = new FakeProvider(
                    answerWith("src/main/java/OrderService.java|30|WARNING|a|b"),
                    null, new java.util.ArrayList<>());

            assertThat(run(provider).additionalFindings()).hasSize(1);
        }

        @Test
        @DisplayName("an exact duplicate is not reported twice")
        void duplicatesCollapsed() throws IOException {
            AiProvider provider = new FakeProvider(
                    answerWith(
                            "src/main/java/OrderService.java|30|WARNING|same|same",
                            "src/main/java/OrderService.java|30|WARNING|same|same"),
                    null, new java.util.ArrayList<>());

            assertThat(run(provider).additionalFindings()).hasSize(1);
        }

        @Test
        @DisplayName("a line of zero is clamped rather than crashing the scan")
        void lineClamped() throws IOException {
            AiProvider provider = new FakeProvider(
                    answerWith("src/main/java/OrderService.java|0|WARNING|a|b"),
                    null, new java.util.ArrayList<>());

            assertThat(run(provider).additionalFindings()).singleElement()
                    .extracting(Finding::line).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the request")
    class TheRequest {

        @Test
        @DisplayName("carries the findings, so the model can judge them")
        void includesFindings() throws IOException {
            List<AiRequest> seen = new java.util.ArrayList<>();
            AiProvider provider = new FakeProvider(AiResponse.empty("fake", "fake-model"), null, seen);

            run(provider);

            assertThat(seen).hasSize(1);
            assertThat(seen.getFirst().findings()).singleElement()
                    .extracting(AiRequest.AiFindingInput::ruleId).isEqualTo("AOP001");
        }

        @Test
        @DisplayName("gives each finding a reference the model can quote back")
        void findingsAreReferenced() throws IOException {
            List<AiRequest> seen = new java.util.ArrayList<>();
            AiProvider provider = new FakeProvider(AiResponse.empty("fake", "fake-model"), null, seen);

            run(provider);

            assertThat(seen.getFirst().findings().getFirst().reference()).isEqualTo("F0");
        }

        @Test
        @DisplayName("never exceeds the caller's budget")
        void respectsBudget() throws IOException {
            Files.createDirectories(projectRoot.resolve("src/main/java"));
            Path source = projectRoot.resolve("src/main/java/OrderService.java");
            Files.writeString(source, "class OrderService {}");

            List<AiRequest> seen = new java.util.ArrayList<>();
            AiProvider provider = new FakeProvider(AiResponse.empty("fake", "fake-model"), null, seen);

            AiReviewService.review(provider, deterministic(), List.of(source), projectRoot, 1, 500);

            assertThat(seen.getFirst().findings()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("failure")
    class Failure {

        @Test
        @DisplayName("a provider that throws does not take the scan down")
        void providerFailure() throws IOException {
            AiProvider provider = new FakeProvider(null,
                    new AiException("fake", "connection refused"), new java.util.ArrayList<>());

            AiReviewService.Outcome outcome = run(provider);

            assertThat(outcome.failed()).isTrue();
            assertThat(outcome.additionalFindings()).isEmpty();
            assertThat(outcome.failureReason()).contains("connection refused");
        }

        @Test
        @DisplayName("a provider that throws something unforeseen is also survivable")
        void unexpectedFailure() throws IOException {
            AiProvider exploding = new AiProvider() {
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
                public AiResponse review(AiRequest request) {
                    throw new IllegalStateException("boom");
                }
            };

            AiReviewService.Outcome outcome = run(exploding);

            assertThat(outcome.failed()).isTrue();
            assertThat(outcome.failureReason()).contains("boom");
        }
    }
}
