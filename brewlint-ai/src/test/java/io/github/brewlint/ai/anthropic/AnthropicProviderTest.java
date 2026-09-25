package io.github.brewlint.ai.anthropic;

import io.github.brewlint.ai.AiException;
import io.github.brewlint.ai.AiRequest;
import io.github.brewlint.ai.AiResponse;
import io.github.brewlint.ai.testing.StubTransport;
import io.github.brewlint.ai.internal.MiniJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covered: request shape, response parsing, and the failure paths.
 *
 * <p>Not covered: a live call to the API. These tests never touch the network, and the point of the
 * {@link StubTransport} is that the expensive, breakable part of this class is the translation, not
 * the socket.
 */
@DisplayName("AnthropicProvider")
class AnthropicProviderTest {

    private static AiRequest request() {
        return AiRequest.builder()
                .projectName("demo")
                .toolVersion("0.1.0")
                .addFinding("AOP001", "ERROR", "src/main/java/OrderService.java", 17,
                        "@Transactional on a private method")
                .addExcerpt("src/main/java/OrderService.java", 1, "class OrderService {}")
                .build();
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Wraps text the way the Messages API does, in a single text content block. */
    private static String messagesResponse(String text) {
        return "{\"content\":[{\"type\":\"text\",\"text\":\"" + escape(text) + "\"}]}";
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {

        @Test
        @DisplayName("without a key it is not configured, and says where to get one")
        void noKey() {
            AnthropicProvider provider = AnthropicProvider.builder().apiKey(null).build();

            assertThat(provider.isConfigured()).isFalse();
            assertThatThrownBy(() -> provider.review(request()))
                    .isInstanceOf(AiException.class)
                    .hasMessageContaining("ANTHROPIC_API_KEY")
                    .hasMessageContaining("--ai ollama");
        }

        @Test
        @DisplayName("a blank key is not configured")
        void blankKey() {
            assertThat(AnthropicProvider.builder().apiKey("   ").build().isConfigured()).isFalse();
        }

        @Test
        @DisplayName("with a key it is configured and names its model")
        void withKey() {
            AnthropicProvider provider = AnthropicProvider.builder()
                    .apiKey("sk-ant-test")
                    .model("claude-test-model")
                    .build();

            assertThat(provider.isConfigured()).isTrue();
            assertThat(provider.name()).isEqualTo("anthropic");
            assertThat(provider.model()).isEqualTo("claude-test-model");
        }

        @Test
        @DisplayName("a blank model falls back to the default rather than sending an empty one")
        void blankModel() {
            assertThat(AnthropicProvider.builder().apiKey("k").model("  ").build().model())
                    .isEqualTo(AnthropicProvider.DEFAULT_MODEL);
        }
    }

    @Nested
    @DisplayName("request")
    class Request {

        @Test
        @DisplayName("goes to the messages endpoint with the version and key headers")
        void headersAndEndpoint() throws Exception {
            StubTransport transport = StubTransport.responding(messagesResponse("{\"verdicts\":[]}"));
            AnthropicProvider provider = AnthropicProvider.builder()
                    .apiKey("sk-ant-secret-value")
                    .transport(transport)
                    .build();

            provider.review(request());

            StubTransport.Call call = transport.onlyCall();
            assertThat(call.uri()).isEqualTo("https://api.anthropic.com/v1/messages");
            assertThat(call.headers())
                    .containsEntry("x-api-key", "sk-ant-secret-value")
                    .containsEntry("anthropic-version", "2023-06-01");
        }

        @Test
        @DisplayName("the body is valid JSON naming the model and one user message")
        void bodyShape() throws Exception {
            StubTransport transport = StubTransport.responding(messagesResponse("{\"verdicts\":[]}"));
            AnthropicProvider provider = AnthropicProvider.builder()
                    .apiKey("k")
                    .model("claude-test-model")
                    .transport(transport)
                    .build();

            provider.review(request());

            var body = MiniJson.asObject(MiniJson.parseLoose(transport.onlyCall().body()));
            assertThat(MiniJson.asString(body.get("model"), "")).isEqualTo("claude-test-model");
            assertThat(MiniJson.asInt(body.get("max_tokens"), 0)).isPositive();

            var messages = MiniJson.asArray(body.get("messages"));
            assertThat(messages).hasSize(1);
            var message = MiniJson.asObject(messages.getFirst());
            assertThat(MiniJson.asString(message.get("role"), "")).isEqualTo("user");
            assertThat(MiniJson.asString(message.get("content"), "")).contains("AOP001");
        }

        @Test
        @DisplayName("the prompt carries the instructions and the source, redacted")
        void promptContent() throws Exception {
            StubTransport transport = StubTransport.responding(messagesResponse("{\"verdicts\":[]}"));
            AnthropicProvider provider = AnthropicProvider.builder()
                    .apiKey("k")
                    .transport(transport)
                    .build();

            provider.review(AiRequest.builder()
                    .addExcerpt("A.java", 1, "String password = \"hunter2\";")
                    .build());

            String sent = transport.onlyCall().body();
            assertThat(sent).doesNotContain("hunter2");
            assertThat(sent).contains("[REDACTED]");
        }
    }

    @Nested
    @DisplayName("response")
    class Response {

        @Test
        @DisplayName("verdicts and suggestions are read out of the text content")
        void parsesAnswers() throws Exception {
            StubTransport transport = StubTransport.responding(messagesResponse("""
                    Here you go:
                    ```json
                    {
                      "verdicts": [
                        {"reference": "F0", "verdict": "FALSE_POSITIVE", "reason": "it is overridden"}
                      ],
                      "suggestions": [
                        {"file": "src/main/java/OrderService.java", "line": 30, "severity": "ERROR",
                         "message": "cache is never invalidated", "suggestion": "add @CacheEvict"}
                      ]
                    }
                    ```"""));

            AiResponse response = AnthropicProvider.builder()
                    .apiKey("k")
                    .transport(transport)
                    .build()
                    .review(request());

            assertThat(response.verdicts()).hasSize(1);
            assertThat(response.verdicts().getFirst().verdict())
                    .isEqualTo(AiResponse.AiVerdict.Verdict.FALSE_POSITIVE);
            assertThat(response.verdicts().getFirst().reason()).isEqualTo("it is overridden");

            assertThat(response.suggestions()).hasSize(1);
            assertThat(response.suggestions().getFirst().line()).isEqualTo(30);
            // The model asked for ERROR. The cap is applied later, when these become findings, and
            // is tested in AiReviewServiceTest.
            assertThat(response.suggestions().getFirst().severity()).isEqualTo("ERROR");
        }

        @Test
        @DisplayName("an empty answer is a valid answer, not a failure")
        void emptyAnswer() throws Exception {
            StubTransport transport = StubTransport.responding(
                    messagesResponse("{\"verdicts\":[],\"suggestions\":[]}"));

            AiResponse response = AnthropicProvider.builder()
                    .apiKey("k")
                    .transport(transport)
                    .build()
                    .review(request());

            assertThat(response.verdicts()).isEmpty();
            assertThat(response.suggestions()).isEmpty();
        }

        @Test
        @DisplayName("an unknown verdict word becomes UNCERTAIN rather than a crash")
        void unknownVerdict() throws Exception {
            StubTransport transport = StubTransport.responding(
                    messagesResponse("{\"verdicts\":[{\"reference\":\"F0\",\"verdict\":\"probably\"}]}"));

            AiResponse response = AnthropicProvider.builder()
                    .apiKey("k")
                    .transport(transport)
                    .build()
                    .review(request());

            assertThat(response.verdicts().getFirst().verdict())
                    .isEqualTo(AiResponse.AiVerdict.Verdict.UNCERTAIN);
        }
    }

    @Nested
    @DisplayName("failures")
    class Failures {

        @Test
        @DisplayName("an HTTP error surfaces the API's own message, not raw JSON")
        void httpError() {
            StubTransport transport = StubTransport.failing(401,
                    "{\"error\":{\"type\":\"authentication_error\","
                            + "\"message\":\"invalid x-api-key\"}}");

            assertThatThrownBy(() -> AnthropicProvider.builder()
                    .apiKey("bad")
                    .transport(transport)
                    .build()
                    .review(request()))
                    .isInstanceOf(AiException.class)
                    .hasMessageContaining("401")
                    .hasMessageContaining("authentication_error")
                    .hasMessageContaining("invalid x-api-key");
        }

        @Test
        @DisplayName("a model that does not exist says so rather than reporting a parse failure")
        void unknownModel() {
            StubTransport transport = StubTransport.failing(404,
                    "{\"error\":{\"type\":\"not_found_error\",\"message\":\"model: claude-nope\"}}");

            assertThatThrownBy(() -> AnthropicProvider.builder()
                    .apiKey("k")
                    .model("claude-nope")
                    .transport(transport)
                    .build()
                    .review(request()))
                    .isInstanceOf(AiException.class)
                    .hasMessageContaining("model: claude-nope");
        }

        @Test
        @DisplayName("an unreachable endpoint is a clear message, not a stack trace")
        void unreachable() {
            assertThatThrownBy(() -> AnthropicProvider.builder()
                    .apiKey("k")
                    .transport(StubTransport.unreachable("connection refused"))
                    .build()
                    .review(request()))
                    .isInstanceOf(AiException.class)
                    .hasMessageContaining("connection refused");
        }

        @Test
        @DisplayName("a non-JSON body is reported as such")
        void nonJsonResponse() {
            StubTransport transport = StubTransport.responding("<html>gateway timeout</html>");

            assertThatThrownBy(() -> AnthropicProvider.builder()
                    .apiKey("k")
                    .transport(transport)
                    .build()
                    .review(request()))
                    .isInstanceOf(AiException.class)
                    .hasMessageContaining("not JSON");
        }

        @Test
        @DisplayName("a response with no text block is a failure, not a silent empty result")
        void noTextContent() {
            StubTransport transport = StubTransport.responding("{\"content\":[]}");

            assertThatThrownBy(() -> AnthropicProvider.builder()
                    .apiKey("k")
                    .transport(transport)
                    .build()
                    .review(request()))
                    .isInstanceOf(AiException.class)
                    .hasMessageContaining("no text content");
        }

        @Test
        @DisplayName("an HTML error body does not produce a wall of markup in the message")
        void htmlErrorIsAbbreviated() {
            StubTransport transport = StubTransport.failing(502, "<html>" + "x".repeat(5000) + "</html>");

            assertThatThrownBy(() -> AnthropicProvider.builder()
                    .apiKey("k")
                    .transport(transport)
                    .build()
                    .review(request()))
                    .isInstanceOf(AiException.class)
                    .satisfies(thrown -> assertThat(thrown.getMessage()).hasSizeLessThan(600));
        }
    }
}
