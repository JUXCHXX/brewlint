package io.github.brewlint.ai.internal;

import io.github.brewlint.ai.AiRequest;

/**
 * The instructions sent to whichever model is answering.
 *
 * <p>Kept in one place on purpose. Anthropic and Ollama take the same text, so the two providers
 * cannot give different advice about the same code, which is the kind of divergence that would be
 * invisible until someone compared their results across machines and got different answers.
 *
 * <h2>What the prompt deliberately does not ask for</h2>
 * It does not ask the model to judge the rules' overall quality, to restate the code, or to invent
 * new rules. Every finding it returns is anchored to a file and line that Brewlint will show, so a
 * user can check it the same way they check a rule finding.
 */
public final class ReviewPrompt {

    private ReviewPrompt() {
    }

    public static String build(AiRequest request) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("""
                You are reviewing a Java Spring Boot codebase for correctness problems that a static
                analyser could not prove. You are a second opinion, not a replacement.

                Rules you must follow:

                1. Only report a problem if you can point at a specific file and line in the code
                   below. If you cannot point at one, do not report it.
                2. Do not repeat anything the static analyser already found. Those findings are
                   given to you for triage, not for duplication.
                3. Prefer fewer, real problems over many speculative ones. An empty result is a
                   correct and acceptable answer.
                4. Never suggest adding a dependency, framework or service.
                5. Keep every message to one sentence, and every suggestion to one sentence with a
                   concrete change.

                """);

        prompt.append("Tool: ").append(request.toolVersion())
                .append("  |  project: ").append(request.projectName())
                .append('\n');

        if (!request.findings().isEmpty()) {
            prompt.append("\n=== FINDINGS ALREADY REPORTED ===\n")
                    .append("For each, answer whether it is a real problem in this code.\n\n");
            for (AiRequest.AiFindingInput finding : request.findings()) {
                prompt.append(finding.reference())
                        .append(" | ").append(finding.ruleId())
                        .append(" | ").append(finding.severity())
                        .append(" | ").append(finding.file()).append(':').append(finding.line())
                        .append("\n  ").append(finding.message()).append('\n');
            }
        }

        if (!request.excerpts().isEmpty()) {
            prompt.append("\n=== SOURCE ===\n");
            for (AiRequest.AiExcerpt excerpt : request.excerpts()) {
                prompt.append("--- ").append(excerpt.file())
                        .append(" (from line ").append(excerpt.startLine()).append(") ---\n")
                        .append(excerpt.content()).append('\n');
            }
        }

        prompt.append("""

                === ANSWER ===
                Reply with one JSON object and nothing else. No prose before it, none after.

                {
                  "verdicts": [
                    {"reference": "F0", "verdict": "CONFIRMED | FALSE_POSITIVE | UNCERTAIN",
                     "reason": "one sentence"}
                  ],
                  "suggestions": [
                    {"file": "path/relative/to/project/root.java", "line": 42,
                     "severity": "ERROR | WARNING | INFO",
                     "message": "one sentence", "suggestion": "one sentence"}
                  ]
                }

                Include a verdict for every reference listed above. Include a suggestion only when
                you can name a file and line that appear in the source above.
                """);

        return prompt.toString();
    }
}
