package io.github.brewlint.ai;

/**
 * Something that can look at code and say what it thinks is wrong with it.
 *
 * <p>Two implementations ship in Hito 4: {@code AnthropicProvider}, which calls a remote API with the
 * user's own key, and {@code OllamaProvider}, which calls a model running on the same machine.
 * Nothing else in Brewlint knows the difference.
 *
 * <h2>The rules this contract exists to enforce</h2>
 * <ol>
 *   <li><strong>Optional by construction.</strong> {@code brewlint-core} does not depend on this
 *       module, so the engine cannot require a provider and the report is complete with no key and
 *       no network. If a scan produces a different set of findings depending on whether a model was
 *       reachable, the report is lying about how much the rules found.</li>
 *   <li><strong>Explicit opt-in.</strong> A provider is constructed only when the user asked for it
 *       by name. There is no configuration that turns it on silently.</li>
 *   <li><strong>Redacted.</strong> Anything a provider is asked about has already been through
 *       {@link SecretRedactor}. Implementations should treat that as the contract, not re-derive it.</li>
 *   <li><strong>Bounded.</strong> A provider works from an {@link AiRequest} with a hard cap on how
 *       much it is asked to read. An unbounded linter is an unbounded bill.</li>
 *   <li><strong>Untrusted output.</strong> A model returns text that becomes findings. It is parsed
 *       defensively and anything unparseable is dropped rather than guessed at.</li>
 * </ol>
 */
public interface AiProvider {

    /** Short name used on the command line, e.g. {@code anthropic}. */
    String name();

    /** The model this provider will use, for logging. A user paying per token deserves to know. */
    String model();

    /**
     * Whether the provider has everything it needs.
     *
     * <p>For a remote provider that means a key is present. For a local one it may also mean the
     * server is reachable, which is a network call, so implementations should keep this cheap and
     * let {@link #review(AiRequest)} surface a real connection failure instead.
     */
    boolean isConfigured();

    /**
     * Reviews the code described by {@code request}.
     *
     * @throws AiException if the provider is unreachable, refuses the request, or answers with
     *                     something unusable. Never throws for an empty or harmless result: a model
     *                     that finds nothing is a valid answer.
     */
    AiResponse review(AiRequest request) throws AiException;
}
