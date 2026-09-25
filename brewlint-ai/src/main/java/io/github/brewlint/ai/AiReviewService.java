package io.github.brewlint.ai;

import io.github.brewlint.core.model.AnalysisResult;
import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.FindingSource;
import io.github.brewlint.core.model.Severity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the optional AI pass and turns the answer into something the report can show.
 *
 * <p>This class is where the trust boundary lives, and it is deliberately conservative. A model is
 * good at noticing things a syntax tree cannot show and bad at being certain about locations,
 * severities and file paths. So:
 *
 * <ul>
 *   <li><strong>AI findings never outrank a warning.</strong> A rule can justify an error because it
 *       decided something. A model claiming an error is a model claiming to have decided something,
 *       and its severity is capped at {@link #MAX_AI_SEVERITY} no matter what it asked for.</li>
 *   <li><strong>A hallucinated path is dropped, not shown.</strong> Every suggested file has to be
 *       one that was actually scanned. Without this check, a report would point at code that does
 *       not exist, which is worse than saying nothing.</li>
 *   <li><strong>Verdicts are kept even when they are inconvenient.</strong> A model saying a rule
 *       finding is a false positive is exactly the signal a user wants, and hiding it would defeat
 *       the purpose of asking.</li>
 * </ul>
 *
 * <p>Failure is not fatal. If the provider is unreachable the deterministic report is still correct
 * and still complete, which is the property that makes this feature optional in the first place.
 */
public final class AiReviewService {

    /**
     * The ceiling for anything a model produces.
     *
     * <p>A deterministic finding can be an {@code ERROR} because it was derived from the syntax
     * tree. An AI finding cannot, and a report that mixes unverified guesses in with proven defects
     * at the same severity teaches users to ignore both.
     */
    public static final Severity MAX_AI_SEVERITY = Severity.WARNING;

    /** Rule id given to every AI finding, so a consumer can filter by source. */
    public static final String AI_RULE_ID = "AI001";

    public static final String AI_CATEGORY = "ai";

    /** How much code to read by default. Enough to be useful, small enough to be cheap. */
    public static final int DEFAULT_MAX_EXCERPT_LINES = 1200;

    /** How many findings to ask about by default. */
    public static final int DEFAULT_MAX_FINDINGS = 50;

    /**
     * Line window around each finding to include as context.
     *
     * <p>Ten lines either side is usually the whole method, which is what a reviewer would read.
     */
    private static final int CONTEXT_LINES = 10;

    private AiReviewService() {
    }

    /**
     * The result of an AI pass.
     *
     * @param additionalFindings suggestions the model made, already validated and capped
     * @param verdicts            the model's judgement on each deterministic finding, keyed by
     *                            finding reference
     * @param failed              true when the provider could not be reached or refused
     * @param failureReason       why, when {@code failed}
     */
    public record Outcome(
            List<Finding> additionalFindings,
            Map<String, AiResponse.AiVerdict> verdicts,
            String provider,
            String model,
            boolean failed,
            String failureReason) {

        public static Outcome failed(String provider, String model, String reason) {
            return new Outcome(List.of(), Map.of(), provider, model, true, reason);
        }

        public boolean hasSuggestions() {
            return !additionalFindings.isEmpty();
        }

        public boolean hasVerdicts() {
            return !verdicts.isEmpty();
        }
    }

    /**
     * Runs {@code provider} over {@code deterministic} and the source files behind it.
     *
     * @param files the same files the engine scanned, used both for context and to validate the
     *              paths a model suggests
     */
    public static Outcome review(
            AiProvider provider,
            AnalysisResult deterministic,
            List<Path> files,
            Path projectRoot,
            int maxFindings,
            int maxExcerptLines) {

        Set<String> scannedFiles = new LinkedHashSet<>();
        for (Path file : files) {
            scannedFiles.add(relativePath(projectRoot, file));
        }

        AiRequest request = buildRequest(provider, deterministic, files, projectRoot,
                maxFindings, maxExcerptLines);

        AiResponse response;
        try {
            response = provider.review(request);
        } catch (AiException exception) {
            return Outcome.failed(provider.name(), provider.model(), exception.getMessage());
        } catch (RuntimeException exception) {
            // A provider that throws something unforeseen must not take the scan down with it.
            return Outcome.failed(provider.name(), provider.model(),
                    "unexpected failure: " + exception);
        }

        return new Outcome(
                toFindings(response.suggestions(), scannedFiles),
                indexVerdicts(response.verdicts()),
                provider.name(),
                provider.model(),
                false,
                null);
    }

    private static AiRequest buildRequest(
            AiProvider provider,
            AnalysisResult deterministic,
            List<Path> files,
            Path projectRoot,
            int maxFindings,
            int maxExcerptLines) {

        AiRequest.Builder builder = AiRequest.builder()
                .projectName(projectRoot.getFileName() == null
                        ? "project" : projectRoot.getFileName().toString())
                .toolVersion(deterministic.toolVersion())
                .maxFindings(maxFindings)
                .maxExcerptLines(maxExcerptLines);

        // Index the findings so the model can quote a reference back.
        List<Finding> sent = deterministic.findings().stream().limit(maxFindings).toList();
        for (Finding finding : sent) {
            builder.addFinding(finding.ruleId(), finding.severity().name(),
                    finding.filePath(), finding.line(), finding.message());
        }

        // Context for the findings we are asking about, then whatever budget is left.
        Set<String> withContext = new LinkedHashSet<>();
        for (Finding finding : sent) {
            withContext.add(finding.filePath());
        }
        for (String file : withContext) {
            Path path = projectRoot.resolve(file);
            builder.addExcerpt(file, 1, excerptAround(path, linesAround(deterministic, file)));
        }
        for (Path file : files) {
            String relative = relativePath(projectRoot, file);
            if (withContext.contains(relative)) {
                continue;
            }
            builder.addExcerpt(relative, 1, excerptAround(file, 0));
        }
        return builder.build();
    }

    private static int linesAround(AnalysisResult deterministic, String file) {
        return deterministic.findings().stream()
                .filter(finding -> finding.filePath().equals(file))
                .mapToInt(Finding::line)
                .min()
                .orElse(1);
    }

    /**
     * Reads at most {@code contextAround} lines either side of {@code focusLine}, starting from the
     * top of the file when {@code focusLine} is zero.
     */
    private static String excerptAround(Path file, int focusLine) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (focusLine <= 0) {
                return String.join("\n", lines);
            }
            int start = Math.max(0, focusLine - 1 - CONTEXT_LINES);
            int end = Math.min(lines.size(), focusLine + CONTEXT_LINES);
            if (start >= end) {
                return "";
            }
            return String.join("\n", lines.subList(start, end));
        } catch (IOException | RuntimeException exception) {
            return "";
        }
    }

    /**
     * Converts suggestions to findings, dropping any whose file was not part of the scan and capping
     * every severity.
     */
    private static List<Finding> toFindings(
            List<AiResponse.AiSuggestion> suggestions, Set<String> scannedFiles) {

        List<Finding> findings = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (AiResponse.AiSuggestion suggestion : suggestions) {
            String file = suggestion.file().replace('\\', '/');
            if (!scannedFiles.contains(file)) {
                // A path that was never scanned is a hallucination. Showing it would put a line
                // number in a report that points at nothing.
                continue;
            }
            if (!seen.add(file + ":" + suggestion.line() + ":" + suggestion.message())) {
                continue;
            }
            findings.add(new Finding(
                    AI_RULE_ID,
                    AI_CATEGORY,
                    cap(suggestion.severity()),
                    file,
                    Math.max(1, suggestion.line()),
                    1,
                    Math.max(1, suggestion.line()),
                    suggestion.message(),
                    suggestion.suggestion(),
                    FindingSource.AI));
        }
        return findings;
    }

    /** Applies {@link #MAX_AI_SEVERITY}. */
    static Severity cap(String requested) {
        Severity parsed;
        try {
            parsed = Severity.parse(requested);
        } catch (RuntimeException exception) {
            return Severity.INFO;
        }
        if (parsed.weight() > MAX_AI_SEVERITY.weight()) {
            return MAX_AI_SEVERITY;
        }
        return parsed;
    }

    private static Map<String, AiResponse.AiVerdict> indexVerdicts(
            List<AiResponse.AiVerdict> verdicts) {
        Map<String, AiResponse.AiVerdict> index = new LinkedHashMap<>();
        for (AiResponse.AiVerdict verdict : verdicts) {
            index.put(verdict.reference(), verdict);
        }
        return index;
    }

    private static String relativePath(Path projectRoot, Path file) {
        Path normalisedRoot = projectRoot.toAbsolutePath().normalize();
        Path normalisedFile = file.toAbsolutePath().normalize();
        if (normalisedFile.startsWith(normalisedRoot)) {
            return normalisedRoot.relativize(normalisedFile).toString().replace('\\', '/');
        }
        return normalisedFile.toString().replace('\\', '/');
    }
}
