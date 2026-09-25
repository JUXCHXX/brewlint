/**
 * Placeholder for Hito 4.
 *
 * <p>This module will hold the optional AI layer: the {@code AiProvider} contract, an Anthropic
 * provider, and a local Ollama provider. It is deliberately empty until then.
 *
 * <p>Two properties are already fixed by the design and must survive into Hito 4:
 *
 * <ol>
 *   <li><strong>AI is never required.</strong> Every deterministic finding must stand on its own,
 *       and the report must be complete with no API key configured. The engine in
 *       {@code brewlint-core} must not depend on this module.</li>
 *   <li><strong>Nothing leaves the machine silently.</strong> Sending source to a remote API is
 *       opt-in, explicit, and redacts secrets first. A local provider must be a first-class
 *       option, not an afterthought.</li>
 * </ol>
 */
package io.github.brewlint.ai;
