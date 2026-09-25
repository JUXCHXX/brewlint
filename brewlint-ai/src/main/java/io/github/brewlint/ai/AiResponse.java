package io.github.brewlint.ai;

import java.util.List;
import java.util.Optional;

/**
 * What a model said.
 *
 * <p>Untrusted by construction. Every field here came from a language model, so the parsers that
 * build this type are defensive: a malformed number becomes 1, an unknown verdict becomes
 * {@code UNCERTAIN}, and an entry with no file is dropped rather than guessed at.
 */
public record AiResponse(
        String provider,
        String model,
        List<AiVerdict> verdicts,
        List<AiSuggestion> suggestions) {

    public AiResponse {
        verdicts = List.copyOf(verdicts);
        suggestions = List.copyOf(suggestions);
    }

    /** A response that says nothing was found, which is a normal and useful answer. */
    public static AiResponse empty(String provider, String model) {
        return new AiResponse(provider, model, List.of(), List.of());
    }

    /** The model's judgement on one deterministic finding. */
    public record AiVerdict(String reference, Verdict verdict, String reason) {

        public enum Verdict {
            /** The finding is real. */
            CONFIRMED,
            /** The finding does not apply here, and the reason says why. */
            FALSE_POSITIVE,
            /** The model could not tell. Kept in the report rather than hidden. */
            UNCERTAIN;

            public static Verdict parse(String raw) {
                if (raw == null) {
                    return UNCERTAIN;
                }
                return switch (raw.trim().toUpperCase(java.util.Locale.ROOT)) {
                    case "CONFIRMED", "TRUE", "VALID" -> CONFIRMED;
                    case "FALSE_POSITIVE", "FALSE POSITIVE", "FALSEPOSITIVE", "FALSE" -> FALSE_POSITIVE;
                    default -> UNCERTAIN;
                };
            }
        }
    }

    /**
     * A problem the model believes exists and the rules did not report.
     *
     * <p>The severity is advisory and is capped when it becomes a {@link io.github.brewlint.core.model.Finding}.
     * A model that claims a problem is an {@code ERROR} is a model that is wrong about being right.
     */
    public record AiSuggestion(String file, int line, String severity, String message, String suggestion) {

        public Optional<String> messageIfPresent() {
            return message == null || message.isBlank() ? Optional.empty() : Optional.of(message);
        }
    }
}
