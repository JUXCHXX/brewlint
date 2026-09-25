package io.github.brewlint.ai.anthropic;

import io.github.brewlint.ai.AiException;
import io.github.brewlint.ai.AiProvider;
import io.github.brewlint.ai.AiRequest;
import io.github.brewlint.ai.AiResponse;
import io.github.brewlint.ai.internal.MiniJson;
import io.github.brewlint.ai.internal.ResponseParser;
import io.github.brewlint.ai.internal.ReviewPrompt;
import io.github.brewlint.ai.internal.JsonWriter;
import io.github.brewlint.ai.transport.HttpTransport;
import io.github.brewlint.ai.transport.JdkHttpTransport;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Talks to the Anthropic Messages API.
 *
 * <p>Code leaves the machine, so two things are load-bearing here. The key comes from the
 * environment and is never written to a file or echoed. And the request is built by
 * {@link ReviewPrompt} over content that has already been through the secret redactor, so a user
 * passing {@code --ai anthropic} is not consenting to shipping their passwords along with it.
 */
public final class AnthropicProvider implements AiProvider {

    public static final String NAME = "anthropic";

    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String API_VERSION = "2023-06-01";

    /**
     * The only model id verified against the published model list, used as the default.
     *
     * <p>Model ids turn over, and guessing one costs a failed paid request per scan. The default is
     * therefore documented as a floor rather than a recommendation: a linter triage pass is a small
     * job, and {@code --ai-model} should point at a current small model for routine use. The
     * authoritative list is {@code GET https://api.anthropic.com/v1/models}.
     */
    public static final String DEFAULT_MODEL = "claude-opus-5";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_OUTPUT_TOKENS = 4096;

    private final String apiKey;
    private final String model;
    private final String endpoint;
    private final Duration timeout;
    private final HttpTransport transport;

    private AnthropicProvider(Builder builder) {
        this.apiKey = builder.apiKey;
        this.model = builder.model;
        this.endpoint = builder.endpoint;
        this.timeout = builder.timeout;
        this.transport = builder.transport;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Reads the key from {@code ANTHROPIC_API_KEY}, the conventional variable. */
    public static String apiKeyFromEnvironment() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        return key == null || key.isBlank() ? null : key.trim();
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
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public AiResponse review(AiRequest request) throws AiException {
        if (!isConfigured()) {
            throw new AiException(NAME,
                    "no API key. Set ANTHROPIC_API_KEY, or use --ai ollama to run a model locally.");
        }

        HttpTransport.Response response;
        try {
            response = transport.post(endpoint, headers(), JsonWriter.write(body(request)), timeout);
        } catch (IOException exception) {
            throw new AiException(NAME, "could not reach " + endpoint + ": " + exception.getMessage(),
                    exception);
        }

        if (response.statusCode() / 100 != 2) {
            throw new AiException(NAME, describeFailure(response));
        }

        return parse(response.body());
    }

    private Map<String, String> headers() {
        return Map.of(
                "x-api-key", apiKey,
                "anthropic-version", API_VERSION,
                "accept", "application/json");
    }

    /**
     * The request body as a structure, not as text.
     *
     * <p>Returning the Map and letting the caller serialise it is deliberate. An earlier version
     * returned a serialised String and the caller serialised it again, which produced a valid JSON
     * document whose entire body was one quoted string containing JSON. The API would have rejected
     * it, and the shape was wrong in a way that parses fine locally.
     */
    private Map<String, Object> body(AiRequest request) {
        // The Messages API takes a flat message list with one user turn. The instructions and the
        // code travel together, so a cached prompt would save tokens on repeat runs; that is a
        // Hito 5 optimisation and not worth the complexity here.
        return Map.of(
                "model", model,
                "max_tokens", MAX_OUTPUT_TOKENS,
                "messages", List.of(Map.of(
                        "role", "user",
                        "content", ReviewPrompt.build(request))));
    }

    private String describeFailure(HttpTransport.Response response) {
        String body = response.body() == null ? "" : response.body();
        // The API puts the useful part in an "error" object. Pulling the message out means the user
        // sees "invalid model" rather than two hundred characters of JSON.
        try {
            Map<String, Object> parsed = MiniJson.asObject(MiniJson.parseLoose(body));
            Map<String, Object> error = MiniJson.asObject(parsed.get("error"));
            String type = MiniJson.asString(error.get("type"), "error");
            String message = MiniJson.asString(error.get("message"), body);
            return "HTTP " + response.statusCode() + " " + type + ": " + message;
        } catch (RuntimeException ignored) {
            return "HTTP " + response.statusCode() + ": " + abbreviate(body);
        }
    }

    private static String abbreviate(String body) {
        String single = body.replaceAll("\\s+", " ").trim();
        return single.length() <= 300 ? single : single.substring(0, 300) + "...";
    }

    AiResponse parse(String responseBody) throws AiException {
        Map<String, Object> envelope;
        try {
            envelope = MiniJson.asObject(MiniJson.parseLoose(responseBody));
        } catch (RuntimeException exception) {
            throw new AiException(NAME, "the response was not JSON: " + exception.getMessage(),
                    exception);
        }

        List<Object> content = MiniJson.asArray(envelope.get("content"));
        String text = content.stream()
                .filter(block -> "text".equals(MiniJson.asObject(block).get("type")))
                .map(block -> MiniJson.asString(MiniJson.asObject(block).get("text"), ""))
                .filter(value -> !value.isEmpty())
                .reduce("", (left, right) -> left + right);

        if (text.isBlank()) {
            throw new AiException(NAME, "the response contained no text content");
        }
        return ResponseParser.parse(NAME, model, text);
    }

    /** Builder, so tests can inject a transport, an endpoint and a timeout. */
    public static final class Builder {

        private String apiKey = apiKeyFromEnvironment();
        private String model = DEFAULT_MODEL;
        private String endpoint = DEFAULT_ENDPOINT;
        private Duration timeout = DEFAULT_TIMEOUT;
        private HttpTransport transport;

        public Builder apiKey(String value) {
            this.apiKey = value;
            return this;
        }

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

        public AnthropicProvider build() {
            if (transport == null) {
                transport = new JdkHttpTransport();
            }
            return new AnthropicProvider(this);
        }
    }
}
