package io.github.brewlint.ai.ollama;

import io.github.brewlint.ai.AiException;
import io.github.brewlint.ai.AiProvider;
import io.github.brewlint.ai.AiRequest;
import io.github.brewlint.ai.AiResponse;
import io.github.brewlint.ai.internal.JsonWriter;
import io.github.brewlint.ai.internal.MiniJson;
import io.github.brewlint.ai.internal.ResponseParser;
import io.github.brewlint.ai.internal.ReviewPrompt;
import io.github.brewlint.ai.transport.HttpTransport;
import io.github.brewlint.ai.transport.JdkHttpTransport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Talks to Ollama running on the same machine.
 *
 * <p>This is the provider for code that cannot leave. No key, no account, no per-token bill, and no
 * request crossing a network boundary at all. It is a first-class option rather than a fallback
 * because {@link io.github.brewlint.ai.SecretRedactor} cannot find every secret: a password typed
 * as a bare string literal is not shaped like anything a pattern can match. For code under an NDA,
 * or in an air-gapped environment, "nothing leaves the machine" is a guarantee and "we redacted the
 * things we recognise" is not.
 *
 * <p>The default model is a placeholder on purpose. Model availability depends entirely on what the
 * user has pulled, so {@code --ai-model} is how you say which one, and the error message when it is
 * missing names the command that lists what is there.
 */
public final class OllamaProvider implements AiProvider {

    public static final String NAME = "ollama";

    /** The local endpoint Ollama listens on by default. */
    public static final String DEFAULT_ENDPOINT = "http://localhost:11434/api/generate";

    /**
     * A small coding model. Whatever is actually available depends on the machine, and this value
     * only has to be a sane starting point; {@code --ai-model} is the real control.
     */
    public static final String DEFAULT_MODEL = "qwen2.5-coder:7b";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(3);

    private final String model;
    private final String endpoint;
    private final Duration timeout;
    private final HttpTransport transport;

    private OllamaProvider(Builder builder) {
        this.model = builder.model;
        this.endpoint = builder.endpoint;
        this.timeout = builder.timeout;
        this.transport = builder.transport;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String model() {
        return model;
    }

    @Override
    public boolean isConfigured() {
        // Nothing to configure. Whether the server is actually running is a network question, and
        // answering it here would mean a request on every --list-rules.
        return true;
    }

    @Override
    public AiResponse review(AiRequest request) throws AiException {
        HttpTransport.Response response;
        try {
            response = transport.post(endpoint, Map.of(), JsonWriter.write(body(request)), timeout);
        } catch (IOException exception) {
            // Not every IOException carries a message, and "(null)" in an error a user is trying to
            // act on is worse than saying nothing.
            String cause = exception.getMessage() == null || exception.getMessage().isBlank()
                    ? exception.getClass().getSimpleName()
                    : exception.getMessage();
            throw new AiException(NAME,
                    "could not reach Ollama at " + endpoint + " (" + cause
                            + "). Is it running? Try: ollama serve",
                    exception);
        }

        if (response.statusCode() / 100 != 2) {
            throw new AiException(NAME, describeFailure(response));
        }

        String text = extractText(response.body());
        if (text.isBlank()) {
            throw new AiException(NAME, "the response contained no text");
        }
        return ResponseParser.parse(NAME, model, text);
    }

    private Map<String, Object> body(AiRequest request) {
        return Map.of(
                "model", model,
                "prompt", ReviewPrompt.build(request),
                "stream", false,
                // Small, deterministic output. A code review is not a task that benefits from a
                // rambling answer, and the JSON parser downstream is not generous.
                "options", Map.of("temperature", 0.1, "num_predict", 4096));
    }

    /** Ollama nests the answer in {@code response}; the shape differs from Anthropic's. */
    private String extractText(String body) throws AiException {
        try {
            Map<String, Object> parsed = MiniJson.asObject(MiniJson.parseLoose(body));
            return MiniJson.asString(parsed.get("response"), "");
        } catch (RuntimeException exception) {
            throw new AiException(NAME, "the response was not JSON: " + exception.getMessage(),
                    exception);
        }
    }

    private String describeFailure(HttpTransport.Response response) {
        String body = response.body() == null ? "" : response.body();
        try {
            String error = MiniJson.asString(MiniJson.asObject(MiniJson.parseLoose(body)).get("error"), "");
            if (!error.isEmpty()) {
                return "HTTP " + response.statusCode() + ": " + error
                        + ". If the model is not pulled, run: ollama pull " + model;
            }
        } catch (RuntimeException ignored) {
            // Not a JSON error body; fall through to the raw text.
        }
        return "HTTP " + response.statusCode() + ": " + body;
    }

    /** Builder, so tests can inject a transport and an endpoint. */
    public static final class Builder {

        private String model = DEFAULT_MODEL;
        private String endpoint = DEFAULT_ENDPOINT;
        private Duration timeout = DEFAULT_TIMEOUT;
        private HttpTransport transport;

        public Builder model(String value) {
            this.model = value == null || value.isBlank() ? DEFAULT_MODEL : value.trim();
            return this;
        }

        public Builder endpoint(String value) {
            this.endpoint = value;
            return this;
        }

        public Builder timeout(Duration value) {
            this.timeout = value;
            return this;
        }

        public Builder transport(HttpTransport value) {
            this.transport = value;
            return this;
        }

        public OllamaProvider build() {
            if (transport == null) {
                transport = new JdkHttpTransport();
            }
            return new OllamaProvider(this);
        }
    }

    /** Exposed for the test that keeps the two providers' model constants from drifting. */
    public static List<String> defaults() {
        return List.of(DEFAULT_ENDPOINT, DEFAULT_MODEL);
    }
}
