package io.github.brewlint.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes credentials from text before it is sent anywhere.
 *
 * <p>This is the component that decides whether the AI feature is safe to offer at all. Every byte
 * that leaves the machine passes through here first, so the class is written to be paranoid and,
 * more importantly, to be tested against the shapes that actually turn up in a Spring codebase.
 *
 * <h2>Why a whitelist of patterns is not enough on its own</h2>
 * No list of secret formats covers every credential ever invented, and a linter that quietly
 * misses one is worse than no linter, because the user believes their code did not leave. So there
 * are two layers:
 * <ol>
 *   <li>Named patterns for the specific formats that are common and recognisable: cloud keys,
 *       private keys, JWTs, tokens with a recognisable prefix, credentials embedded in a URL.</li>
 *   <li>A generic catch-all for assignments, so {@code password = "hunter2"} and
 *       {@code apiToken: 'abc'} are caught even when the format is one nobody has seen before.</li>
 * </ol>
 *
 * <h2>What redaction cannot do</h2>
 * It cannot find a secret that is not shaped like anything: a password typed as a bare string
 * literal in the middle of a method, or a name nobody used. That is a real limit and the reason
 * {@code --ai ollama} exists as a first-class option rather than a fallback. A user with code they
 * cannot send anywhere should be able to run the same analysis locally with nothing leaving the
 * machine, and not have to reason about how thorough the patterns are.
 */
public final class SecretRedactor {

    /** What a redacted secret is replaced with. Kept identical in length-free form. */
    public static final String REDACTED = "[REDACTED]";

    private static final Map<String, Pattern> PATTERNS = patterns();

    private SecretRedactor() {
    }

    private static Map<String, Pattern> patterns() {
        Map<String, Pattern> patterns = new LinkedHashMap<>();

        // A PEM block, header to footer. Matched as a block so the base64 payload goes with it.
        patterns.put("private-key", Pattern.compile(
                "-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----",
                Pattern.DOTALL));

        // Credentials inside a connection URL: jdbc:mysql://user:password@host, and the same for
        // postgres, mongodb, redis and amqp. Also plain https://user:password@host.
        patterns.put("url-credentials", Pattern.compile(
                "([a-zA-Z][a-zA-Z0-9+.-]*://)([^/\\s:@]+):([^/\\s@]+)@"));

        // Cloud provider keys with a fixed prefix. The prefix is the reliable part; the rest is
        // a fixed length per provider.
        patterns.put("aws-access-key", Pattern.compile("\\b(?:AKIA|ASIA|ABIA|ACCA)[0-9A-Z]{16}\\b"));
        patterns.put("github-token", Pattern.compile("\\b(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{36,}\\b"));
        patterns.put("github-pat", Pattern.compile("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"));
        patterns.put("anthropic-key", Pattern.compile("\\bsk-ant-[A-Za-z0-9_-]{16,}\\b"));
        patterns.put("openai-key", Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\\b"));
        patterns.put("slack-token", Pattern.compile("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b"));
        patterns.put("google-api-key", Pattern.compile("\\bAIza[0-9A-Za-z_-]{35}\\b"));
        patterns.put("stripe-key", Pattern.compile("\\b[sr]k_(?:live|test)_[A-Za-z0-9]{16,}\\b"));
        patterns.put("npm-token", Pattern.compile("\\bnpm_[A-Za-z0-9]{36}\\b"));

        // A JWT. Three base64url segments.
        patterns.put("jwt", Pattern.compile("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b"));

        // An Authorization header value pasted into source.
        patterns.put("bearer-token", Pattern.compile(
                "(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{12,}"));

        // The generic layer. A name that means a credential, an assignment operator, a quoted value.
        // The name list is the security-sensitive part: too short and ordinary locals like "id" or
        // "name" start getting redacted, which makes the report useless.
        //
        // The unquoted value alternative carries a negative lookahead so that
        // "String token = tokenService.issue();" is left alone. That is a call, not a credential,
        // and blanking it would remove exactly the context a reviewer needs while protecting
        // nothing. Quoted values are always redacted, because a quoted secret is a secret.
        patterns.put("credential-assignment", Pattern.compile(
                "(?i)\\b("
                        + "api[_-]?key|apikey|api[_-]?secret|secret[_-]?key|secret|access[_-]?key"
                        + "|access[_-]?token|refresh[_-]?token|auth[_-]?token|token"
                        + "|client[_-]?secret|private[_-]?key|passwd|password|passphrase|pwd"
                        + "|credentials?|connection[_-]?string|dsn|salt|signing[_-]?key"
                        + ")\\b(\\s*[:=]\\s*)"
                        + "(\"[^\"\\n]*\"|'[^'\\n]*'"
                        + "|(?![A-Za-z0-9_.]*\\()[^\\s,;)\\]}]+)"));

        return Map.copyOf(patterns);
    }

    /**
     * Returns {@code text} with anything that looks like a credential replaced.
     *
     * <p>Idempotent: redacting already-redacted text changes nothing, so a caller can safely run it
     * twice over the same content.
     */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // The PEM block is handled first because it is multi-line: redacting it after the single
        // line patterns would have already rewritten the base64 payload and broken the delimiters.
        String result = PATTERNS.get("private-key").matcher(text)
                .replaceAll(Matcher.quoteReplacement(REDACTED));

        for (Map.Entry<String, Pattern> entry : PATTERNS.entrySet()) {
            if (entry.getKey().equals("private-key")) {
                continue;
            }
            result = applyPattern(entry.getKey(), result);
        }
        return result;
    }

    private static String applyPattern(String name, String text) {
        var matcher = PATTERNS.get(name).matcher(text);
        if (!matcher.find()) {
            return text;
        }
        // Group references are written raw, not through quoteReplacement: that method exists to
        // escape a literal $ or \ in a replacement, and applying it to "$1" would insert the text
        // "$1" instead of the matched group. The literal parts below contain neither character, so
        // they are safe unescaped.
        return switch (name) {
            // Keep the scheme, drop the user and the password. "jdbc:mysql://[REDACTED]@host" is
            // still useful context for a reviewer; the whole URL is not.
            case "url-credentials" -> matcher.replaceAll("$1" + REDACTED + "@");
            // Keep the name and the punctuation, drop the value, so the report still says that this
            // line had a password on it.
            case "credential-assignment" -> matcher.replaceAll("$1$2" + '"' + REDACTED + '"');
            case "bearer-token" -> matcher.replaceAll("$1 " + REDACTED);
            default -> matcher.replaceAll(Matcher.quoteReplacement(REDACTED));
        };
    }

    /** The names of every pattern applied, for documentation and for the test that covers them. */
    public static List<String> patternNames() {
        return List.copyOf(PATTERNS.keySet());
    }
}
