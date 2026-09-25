package io.github.brewlint.ai.internal;

import io.github.brewlint.ai.AiException;
import io.github.brewlint.ai.AiResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns a model's text answer into an {@link AiResponse}.
 *
 * <p>Shared by both providers, because Anthropic and Ollama are asked for the same JSON and must
 * not disagree about how to read it. A model that returns three valid verdicts and one broken entry
 * should cost the broken entry, not the whole review: that is the difference between an optional
 * feature being useful and being switched off.
 */
public final class ResponseParser {

    private ResponseParser() {
    }

    public static AiResponse parse(String provider, String model, String text) throws AiException {
        Object parsed;
        try {
            parsed = MiniJson.parseLoose(text);
        } catch (RuntimeException exception) {
            throw new AiException(provider,
                    "could not read the model's answer as JSON: " + exception.getMessage(), exception);
        }

        Map<String, Object> root = MiniJson.asObject(parsed);
        return new AiResponse(provider, model, parseVerdicts(root), parseSuggestions(root));
    }

    private static List<AiResponse.AiVerdict> parseVerdicts(Map<String, Object> root) {
        List<AiResponse.AiVerdict> verdicts = new ArrayList<>();
        for (Object element : MiniJson.asArray(root.get("verdicts"))) {
            Map<String, Object> entry = MiniJson.asObject(element);
            String reference = MiniJson.asString(entry.get("reference"), "");
            if (reference.isEmpty()) {
                // A verdict with nothing to attach to is not actionable.
                continue;
            }
            verdicts.add(new AiResponse.AiVerdict(
                    reference,
                    AiResponse.AiVerdict.Verdict.parse(MiniJson.asString(entry.get("verdict"), null)),
                    MiniJson.asString(entry.get("reason"), "")));
        }
        return verdicts;
    }

    private static List<AiResponse.AiSuggestion> parseSuggestions(Map<String, Object> root) {
        List<AiResponse.AiSuggestion> suggestions = new ArrayList<>();
        for (Object element : MiniJson.asArray(root.get("suggestions"))) {
            Map<String, Object> entry = MiniJson.asObject(element);
            String file = MiniJson.asString(entry.get("file"), "");
            String message = MiniJson.asString(entry.get("message"), "");
            if (file.isEmpty() || message.isEmpty()) {
                // The prompt requires both. An entry missing either cannot be shown to a user in a
                // report that has to point at a location, so it is dropped rather than invented.
                continue;
            }
            suggestions.add(new AiResponse.AiSuggestion(
                    file,
                    // Clamped to 1 so a model that answered 0 or -1 does not produce a Finding whose
                    // constructor rejects it and takes down the whole scan.
                    Math.max(1, MiniJson.asInt(entry.get("line"), 1)),
                    MiniJson.asString(entry.get("severity"), "INFO"),
                    message,
                    MiniJson.asString(entry.get("suggestion"), "Review this manually.")));
        }
        return suggestions;
    }
}
