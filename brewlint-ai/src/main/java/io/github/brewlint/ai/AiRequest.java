package io.github.brewlint.ai;

import java.util.List;

/**
 * What a provider is asked to look at.
 *
 * <p>Bounded on purpose. The most common way an optional AI feature goes wrong is not a security
 * problem, it is a cost one: a linter that silently sends a whole repository to a paid API on every
 * run. {@code maxFindings} and {@code maxExcerptLines} are therefore part of the request, not a
 * setting, so a provider cannot ignore them.
 *
 * <p>Everything here has already been through {@link SecretRedactor}. The caller is responsible for
 * that, and {@link #redactedCopy()} is the only way to build one.
 */
public record AiRequest(
        String projectName,
        String toolVersion,
        List<AiFindingInput> findings,
        List<AiExcerpt> excerpts,
        int maxFindings,
        int maxExcerptLines) {

    /** Upper bound on findings sent for triage. Beyond this the marginal value collapses. */
    public static final int HARD_MAX_FINDINGS = 200;

    /** Upper bound on lines of code sent. Keeps a pathological run from producing a huge bill. */
    public static final int HARD_MAX_EXCERPT_LINES = 4000;

    public AiRequest {
        if (findings == null || excerpts == null) {
            throw new IllegalArgumentException("findings and excerpts are required");
        }
        findings = List.copyOf(findings);
        excerpts = List.copyOf(excerpts);
        if (maxFindings < 0 || maxFindings > HARD_MAX_FINDINGS) {
            throw new IllegalArgumentException(
                    "maxFindings must be between 0 and " + HARD_MAX_FINDINGS + ", got " + maxFindings);
        }
        if (maxExcerptLines < 0 || maxExcerptLines > HARD_MAX_EXCERPT_LINES) {
            throw new IllegalArgumentException("maxExcerptLines must be between 0 and "
                    + HARD_MAX_EXCERPT_LINES + ", got " + maxExcerptLines);
        }
    }

    /** A builder that redacts, so no caller can forget. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * A deterministic finding handed to the model for triage.
     *
     * @param reference an id the model quotes back, so a verdict can be matched to a finding
     */
    public record AiFindingInput(String reference, String ruleId, String severity, String file, int line,
                                 String message) {
    }

    /**
     * A bounded piece of source, already redacted.
     *
     * @param startLine 1-based line of the first line in {@code content}
     */
    public record AiExcerpt(String file, int startLine, String content) {
    }

    /** Assembles a request, redacting every piece of source and text that goes into it. */
    public static final class Builder {

        private String projectName = "project";
        private String toolVersion = "unknown";
        private final List<AiFindingInput> findings = new java.util.ArrayList<>();
        private final List<AiExcerpt> excerpts = new java.util.ArrayList<>();
        private int maxFindings = 50;
        private int maxExcerptLines = 1200;
        private int excerptCounter;

        public Builder projectName(String value) {
            this.projectName = value;
            return this;
        }

        public Builder toolVersion(String value) {
            this.toolVersion = value;
            return this;
        }

        public Builder maxFindings(int value) {
            this.maxFindings = Math.min(value, HARD_MAX_FINDINGS);
            return this;
        }

        public Builder maxExcerptLines(int value) {
            this.maxExcerptLines = Math.min(value, HARD_MAX_EXCERPT_LINES);
            return this;
        }

        /**
         * Adds a finding, with a stable reference. The reference is an index, not a rule id,
         * because the same rule fires many times and the model has to be able to say which.
         */
        public Builder addFinding(String ruleId, String severity, String file, int line, String message) {
            if (findings.size() >= maxFindings) {
                return this;
            }
            findings.add(new AiFindingInput(
                    "F" + findings.size(),
                    ruleId,
                    severity,
                    file,
                    line,
                    SecretRedactor.redact(message)));
            return this;
        }

        /**
         * Adds source, truncated and redacted.
         *
         * <p>Truncation happens before redaction so the limit applies to what the model reads, and
         * redaction happens after so a secret split across the cut is still removed.
         */
        public Builder addExcerpt(String file, int startLine, String content) {
            if (excerpts.isEmpty() && excerptCounter >= maxExcerptLines) {
                return this;
            }
            int remaining = maxExcerptLines - excerptCounter;
            if (remaining <= 0) {
                return this;
            }
            String[] lines = SecretRedactor.redact(content).split("\n", -1);
            int taken = Math.min(lines.length, remaining);
            StringBuilder kept = new StringBuilder();
            for (int index = 0; index < taken; index++) {
                if (index > 0) {
                    kept.append('\n');
                }
                kept.append(lines[index]);
            }
            excerpts.add(new AiExcerpt(file, startLine, kept.toString()));
            excerptCounter += taken;
            return this;
        }

        public AiRequest build() {
            return new AiRequest(projectName, toolVersion, findings, excerpts, maxFindings,
                    maxExcerptLines);
        }
    }
}
