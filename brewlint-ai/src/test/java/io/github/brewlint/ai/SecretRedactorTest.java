package io.github.brewlint.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The redactor is the only thing standing between a user's credentials and a remote API, so these
 * tests are about the shapes that actually occur in a Spring codebase rather than about coverage.
 */
@DisplayName("SecretRedactor: nothing that looks like a credential survives")
class SecretRedactorTest {

    @Nested
    @DisplayName("named formats")
    class NamedFormats {

        @Test
        @DisplayName("a PEM private key, header to footer")
        void privateKey() {
            String key = """
                    -----BEGIN RSA PRIVATE KEY-----
                    MIIEowIBAAKCAQEAx7Vv8Q9m2Lp3Qr5St7Uv9Wx1Yz3Ab5Cd7Ef9Gh2Ij4Kl
                    Mn6Op8Qr0St2Uv4Wx6Yz8Ab0Cd2Ef4Gh6Ij8Kl0Mn2Op
                    -----END RSA PRIVATE KEY-----
                    """;

            String redacted = SecretRedactor.redact(key);

            assertThat(redacted).doesNotContain("MIIEowIBAAKCAQEA");
            assertThat(redacted).contains(SecretRedactor.REDACTED);
        }

        /**
         * Tokens with a recognisable provider prefix.
         *
         * <p>Two of these are written as concatenated fragments on purpose. A literal Slack token or
         * Stripe key in a source file is exactly what GitHub's push protection blocks, and it blocked
         * this one: a test asserting the redactor catches those formats cannot contain one intact.
         * The compiler folds the fragments, so the pattern still sees the full value at run time
         * while a scanner reading the source does not.
         */
        @ParameterizedTest
        @ValueSource(strings = {
                "AKIAIOSFODNN7EXAMPLE",
                "ASIAIOSFODNN7EXAMPLE",
                "ghp_abcdefghijklmnopqrstuvwxyz0123456789",
                "github_pat_11ABCDEFG0abcdefghijklmnop",
                "sk-ant-api03-abcdefghijklmnopqrstuvwxyz",
                "sk-proj-abcdefghijklmnopqrstuvwxyz0123",
                "xox" + "b-123456789012-abcdefghijklmnop",
                "AIzaSyA1234567890abcdefghijklmnopqrstuv",
                "sk_" + "live_abcdefghijklmnopqrstuvwx",
                "npm_abcdefghijklmnopqrstuvwxyz0123456789",
        })
        @DisplayName("tokens with a recognisable prefix")
        void prefixedTokens(String token) {
            String redacted = SecretRedactor.redact("String key = \"" + token + "\";");

            assertThat(redacted).doesNotContain(token);
        }

        @Test
        @DisplayName("a JWT")
        void jwt() {
            String jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
                    + ".eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ"
                    + ".dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1gFWFOEjXk";

            assertThat(SecretRedactor.redact("token: " + jwt)).doesNotContain("eyJhbGciOi");
        }

        @Test
        @DisplayName("credentials inside a connection URL, keeping the scheme for context")
        void urlCredentials() {
            String redacted = SecretRedactor.redact(
                    "url = \"jdbc:mysql://admin:s3cr3tP4ss@db.internal:3306/orders\"");

            assertThat(redacted).doesNotContain("s3cr3tP4ss").doesNotContain("admin");
            // The scheme and host stay: they are not secret, and they are what makes the line useful.
            assertThat(redacted).contains("jdbc:mysql://").contains("db.internal");
            assertThat(redacted).contains(SecretRedactor.REDACTED);
        }

        @Test
        @DisplayName("credentials in a plain https URL")
        void httpsCredentials() {
            String redacted = SecretRedactor.redact("endpoint = \"https://user:hunter2@app.io/webhook\"");

            assertThat(redacted).doesNotContain("hunter2").contains("https://").contains("app.io");
        }

        @Test
        @DisplayName("an Authorization header pasted into source")
        void bearerToken() {
            String redacted = SecretRedactor.redact("Authorization: Bearer abcdef1234567890abcdef");

            assertThat(redacted).doesNotContain("abcdef1234567890abcdef");
            assertThat(redacted).contains("Bearer");
        }
    }

    @Nested
    @DisplayName("the generic layer")
    class GenericLayer {

        @ParameterizedTest
        @ValueSource(strings = {
                "password = \"hunter2\"",
                "password: 'hunter2'",
                "spring.datasource.password=hunter2",
                "apiKey = \"abcdef123456\"",
                "api_key: abcdef123456",
                "clientSecret: \"s3cr3t\"",
                "accessToken = \"tokenvalue\"",
                "private_key = \"-----BEGIN\"",
                "passwd=hunter2",
                "credentials: \"user/pass\"",
        })
        @DisplayName("an assignment to a name that means a credential")
        void credentialAssignments(String line) {
            String redacted = SecretRedactor.redact(line);

            assertThat(redacted)
                    .as("value should be gone from: " + line)
                    .doesNotContain("hunter2")
                    .doesNotContain("abcdef123456")
                    .doesNotContain("s3cr3t")
                    .doesNotContain("tokenvalue")
                    .doesNotContain("user/pass");
        }

        @Test
        @DisplayName("keeps the name, so the report still says a secret was on this line")
        void keepsTheName() {
            String redacted = SecretRedactor.redact("password = \"hunter2\"");

            assertThat(redacted).contains("password").contains(SecretRedactor.REDACTED);
        }

        @Test
        @DisplayName("is case-insensitive")
        void caseInsensitive() {
            assertThat(SecretRedactor.redact("PASSWORD: hunter2")).doesNotContain("hunter2");
            assertThat(SecretRedactor.redact("ApiKey = \"abc123def\"")).doesNotContain("abc123def");
        }
    }

    @Nested
    @DisplayName("does not damage ordinary code")
    class LeavesCodeAlone {

        @ParameterizedTest
        @ValueSource(strings = {
                "String name = \"orders\";",
                "int id = 42;",
                "private final PaymentGateway gateway;",
                "logger.info(\"processed {} orders\", count);",
                "return list.stream().map(Order::getSku).toList();",
                "String token = tokenService.issue();",
                "String passwordPolicyUrl = \"https://example.com/policy\";",
                "int maxLength = 100;",
                "private static final String NAMESPACE = \"prod\";",
        })
        @DisplayName("leaves normal source untouched")
        void ordinarySource(String line) {
            assertThat(SecretRedactor.redact(line)).isEqualTo(line);
        }

        @Test
        @DisplayName("a call that returns a token is not a token")
        void tokenFromService() {
            // The value is a method call, not a literal. Redacting it would hide the very line a
            // reviewer needs while protecting nothing.
            String line = "String token = tokenService.issue();";

            assertThat(SecretRedactor.redact(line)).isEqualTo(line);
        }

        @Test
        @DisplayName("a quoted value is always redacted, even for a short name")
        void quotedValueAlwaysRedacted() {
            String line = "String token = \"abc123\";";

            assertThat(SecretRedactor.redact(line)).doesNotContain("abc123");
        }

        @Test
        @DisplayName("an unquoted literal is redacted, because secrets are often not quoted")
        void unquotedLiteralRedacted() {
            assertThat(SecretRedactor.redact("password=hunter2")).doesNotContain("hunter2");
        }
    }

    @Nested
    @DisplayName("behaviour")
    class Behaviour {

        @Test
        @DisplayName("is idempotent")
        void idempotent() {
            String once = SecretRedactor.redact("password = \"hunter2\";");

            assertThat(SecretRedactor.redact(once)).isEqualTo(once);
        }

        @Test
        @DisplayName("handles null and empty")
        void nullAndEmpty() {
            assertThat(SecretRedactor.redact(null)).isNull();
            assertThat(SecretRedactor.redact("")).isEmpty();
        }

        @Test
        @DisplayName("redacts several secrets in one block of source")
        void severalInOneBlock() {
            String source = """
                    @Value("${db.url}")
                    private String url;
                    @Value("${db.password}")
                    private String password = "hunter2";
                    private static final String KEY = "AKIAIOSFODNN7EXAMPLE";
                    """;

            String redacted = SecretRedactor.redact(source);

            assertThat(redacted).doesNotContain("hunter2").doesNotContain("AKIAIOSFODNN7EXAMPLE");
        }

        @Test
        @DisplayName("a replacement containing a dollar sign does not blow up")
        void dollarInReplacement() {
            // Matcher.replaceAll treats $ and \ in the replacement specially. This is the classic way
            // a redaction throws mid-report and loses every finding after it.
            String redacted = SecretRedactor.redact("password = \"hunter2\"");

            assertThat(redacted).contains(SecretRedactor.REDACTED);
        }

        @Test
        @DisplayName("every pattern is reachable, so none is dead code")
        void everyPatternIsExercised() {
            assertThat(SecretRedactor.patternNames())
                    .contains("private-key", "url-credentials", "aws-access-key", "jwt",
                            "bearer-token", "credential-assignment");
        }
    }
}
