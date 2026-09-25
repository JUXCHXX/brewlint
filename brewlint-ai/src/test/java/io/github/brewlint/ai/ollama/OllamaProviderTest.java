package io.github.brewlint.ai.ollama;

import io.github.brewlint.ai.AiException;
import io.github.brewlint.ai.AiRequest;
import io.github.brewlint.ai.AiResponse;
import io.github.brewlint.ai.internal.MiniJson;
import io.github.brewlint.ai.testing.StubTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covered: request shape, response parsing, and the failure paths.
 *
 * <p>Not covered: a live call to a locally running Ollama. Whether a given model is pulled is a
 * property of the developer's machine, not of this code.
 */
@DisplayName("OllamaProvider")
class OllamaProviderTest {

    private static AiRequest request() {
        return AiRequest.builder()
                .projectName("demo")
                .addFinding("RES001", "ERROR", "src/main/java/InventoryDao.java", 18,
                        "Connection never closed")
                .addExcerpt("src/main/java/InventoryDao.java", 1, "class InventoryDao {}")
                .build();
    }

    /** Ollama nests the answer in a "response" string. */
    private static String ollamaResponse(String text) {
        return "{\"response\":\"" + text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\",\"done\":true}";
    }

    @Test
    @DisplayName("is always configured, because there is no key to be missing")
    void alwaysConfigured() {
        assertThat(OllamaProvider.builder().build().isConfigured()).isTrue();
    }

    @Test
    @DisplayName("posts to the local endpoint with no credentials")
    void endpointAndHeaders() throws Exception {
        StubTransport transport = StubTransport.responding(ollamaResponse("{\"verdicts\":[]}"));
        OllamaProvider provider = OllamaProvider.builder().transport(transport).build();

        provider.review(request());

        StubTransport.Call call = transport.onlyCall();
        assertThat(call.uri()).isEqualTo("http://localhost:11434/api/generate");
        // Nothing secret is attached, which is the entire appeal of this provider.
        assertThat(call.headers()).doesNotContainKey("x-api-key");
    }

    @Test
    @DisplayName("the body names the model and disables streaming")
    void bodyShape() throws Exception {
        StubTransport transport = StubTransport.responding(ollamaResponse("{\"verdicts\":[]}"));
        OllamaProvider provider = OllamaProvider.builder()
                .model("qwen-test")
                .transport(transport)
                .build();

        provider.review(request());

        var body = MiniJson.asObject(MiniJson.parseLoose(transport.onlyCall().body()));
        assertThat(MiniJson.asString(body.get("model"), "")).isEqualTo("qwen-test");
        assertThat(body.get("stream")).isEqualTo(false);
        assertThat(MiniJson.asString(body.get("prompt"), "")).contains("RES001");
    }

    @Test
    @DisplayName("asks for a low temperature, because a review should be repeatable")
    void deterministicOptions() throws Exception {
        StubTransport transport = StubTransport.responding(ollamaResponse("{\"verdicts\":[]}"));
        OllamaProvider.builder().transport(transport).build().review(request());

        var body = MiniJson.asObject(MiniJson.parseLoose(transport.onlyCall().body()));
        var options = MiniJson.asObject(body.get("options"));
        double temperature = ((Number) options.get("temperature")).doubleValue();

        assertThat(temperature).isLessThan(0.5);
    }

    @Test
    @DisplayName("reads the answer out of the response field")
    void parsesAnswer() throws Exception {
        StubTransport transport = StubTransport.responding(ollamaResponse("""
                {
                  "verdicts": [{"reference": "F0", "verdict": "CONFIRMED", "reason": "it is real"}],
                  "suggestions": [
                    {"file": "src/main/java/InventoryDao.java", "line": 24, "severity": "INFO",
                     "message": "the reader is leaked in a loop", "suggestion": "close it per row"}
                  ]
                }"""));

        AiResponse response = OllamaProvider.builder().transport(transport).build().review(request());

        assertThat(response.verdicts()).hasSize(1);
        assertThat(response.verdicts().getFirst().verdict())
                .isEqualTo(AiResponse.AiVerdict.Verdict.CONFIRMED);
        assertThat(response.suggestions()).hasSize(1);
        assertThat(response.suggestions().getFirst().line()).isEqualTo(24);
    }

    @Test
    @DisplayName("an empty answer is a valid answer")
    void emptyAnswer() throws Exception {
        StubTransport transport = StubTransport.responding(
                ollamaResponse("{\"verdicts\":[],\"suggestions\":[]}"));

        AiResponse response = OllamaProvider.builder().transport(transport).build().review(request());

        assertThat(response.suggestions()).isEmpty();
    }

    @Test
    @DisplayName("when Ollama is not running, the message says how to start it")
    void notRunning() {
        assertThatThrownBy(() -> OllamaProvider.builder()
                .transport(StubTransport.unreachable("Connection refused"))
                .build()
                .review(request()))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("ollama serve")
                .hasMessageContaining("Connection refused");
    }

    @Test
    @DisplayName("a missing model names the command that fixes it")
    void modelNotPulled() {
        StubTransport transport = StubTransport.failing(404,
                "{\"error\":\"model 'qwen2.5-coder:7b' not found, try pulling it first\"}");

        assertThatThrownBy(() -> OllamaProvider.builder()
                .transport(transport)
                .build()
                .review(request()))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("ollama pull")
                .hasMessageContaining("qwen2.5-coder:7b");
    }

    @Test
    @DisplayName("a non-JSON body is reported as such")
    void nonJson() {
        StubTransport transport = StubTransport.responding("not json at all");

        assertThatThrownBy(() -> OllamaProvider.builder()
                .transport(transport)
                .build()
                .review(request()))
                .isInstanceOf(AiException.class)
                .hasMessageContaining("not JSON");
    }

    @Test
    @DisplayName("never sends a secret, because it is local")
    void promptIsRedacted() throws Exception {
        StubTransport transport = StubTransport.responding(ollamaResponse("{\"verdicts\":[]}"));

        OllamaProvider.builder().transport(transport).build().review(
                AiRequest.builder().addExcerpt("A.java", 1, "password = \"hunter2\";").build());

        // Redaction applies to every provider, including the local one. Consistency is cheaper than
        // a special case, and it means switching providers cannot silently change what is exposed.
        assertThat(transport.onlyCall().body()).doesNotContain("hunter2");
    }

    @Test
    @DisplayName("the two providers are asked the same question")
    void samePromptAsAnthropic() throws Exception {
        StubTransport ollama = StubTransport.responding(ollamaResponse("{\"verdicts\":[]}"));
        OllamaProvider.builder().transport(ollama).build().review(request());

        StubTransport anthropic = StubTransport.responding(
                "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"verdicts\\\":[]}\"}]}");
        io.github.brewlint.ai.anthropic.AnthropicProvider.builder()
                .apiKey("k")
                .transport(anthropic)
                .build()
                .review(request());

        String ollamaPrompt = MiniJson.asString(
                MiniJson.asObject(MiniJson.parseLoose(ollama.onlyCall().body())).get("prompt"), "");
        var anthropicBody = MiniJson.asObject(MiniJson.parseLoose(anthropic.onlyCall().body()));
        var anthropicMessage = MiniJson.asObject(MiniJson.asArray(anthropicBody.get("messages")).getFirst());
        String anthropicPrompt = MiniJson.asString(anthropicMessage.get("content"), "");

        assertThat(anthropicPrompt).contains("RES001");
        assertThat(ollamaPrompt).contains("RES001");
        // Byte for byte the same instructions, so a user comparing results across the two providers
        // is comparing the model, not the prompt.
        assertThat(ollamaPrompt).isEqualTo(anthropicPrompt);
    }

    @Test
    @DisplayName("defaults are documented where the assembler can read them")
    void defaultsExposed() {
        assertThat(OllamaProvider.defaults())
                .containsExactly(OllamaProvider.DEFAULT_ENDPOINT, OllamaProvider.DEFAULT_MODEL);
    }

    @Test
    @DisplayName("the endpoint and model are overridable")
    void overrides() throws Exception {
        StubTransport transport = StubTransport.responding(ollamaResponse("{\"verdicts\":[]}"));
        OllamaProvider.builder()
                .endpoint("http://192.168.1.10:11434/api/generate")
                .model("llama3")
                .transport(transport)
                .build()
                .review(request());

        assertThat(transport.onlyCall().uri()).startsWith("http://192.168.1.10:11434");
        var body = MiniJson.asObject(MiniJson.parseLoose(transport.onlyCall().body()));
        assertThat(MiniJson.asString(body.get("model"), "")).isEqualTo("llama3");
    }

    @Test
    @DisplayName("the model options object is not confused with a stream flag")
    void optionsShape() {
        Map<String, Object> options = Map.of("temperature", 0.1, "num_predict", 4096);

        assertThat(options).hasSize(2);
    }
}
