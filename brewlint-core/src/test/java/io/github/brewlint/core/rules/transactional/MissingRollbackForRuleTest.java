package io.github.brewlint.core.rules.transactional;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import io.github.brewlint.core.type.CheckedExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TX003: @Transactional without rollbackFor on a method that can throw a checked exception")
class MissingRollbackForRuleTest {

    private final MissingRollbackForRule rule = new MissingRollbackForRule();

    private List<Finding> check(String classBody) {
        return RuleTester.checkInClass(rule, classBody);
    }

    @Test
    @DisplayName("every name in the known-checked list is really a checked exception in the JDK")
    void knownCheckedListIsAccurate() {
        // The list holds simple names because that is all a source file writes. Resolving each one
        // against the JDK is what proves the list is not lying, and it caught "ClassCastException"
        // having been written into it during development.
        List<String> packages = List.of(
                "java.lang.", "java.io.", "java.sql.", "java.net.", "java.text.",
                "java.util.concurrent.", "java.util.zip.", "java.lang.reflect.",
                "java.security.", "javax.crypto.", "javax.xml.", "javax.xml.parsers.",
                "org.xml.sax.");

        for (String name : CheckedExceptions.knownChecked()) {
            Class<?> type = resolve(name, packages);
            assertThat(type).as("%s must be a real JDK class", name).isNotNull();
            assertThat(Throwable.class.isAssignableFrom(type))
                    .as("%s must be a Throwable", name).isTrue();
            assertThat(RuntimeException.class.isAssignableFrom(type))
                    .as("%s must not be unchecked", name).isFalse();
            assertThat(Error.class.isAssignableFrom(type))
                    .as("%s must not be an Error", name).isFalse();
        }
    }

    private static Class<?> resolve(String simpleName, List<String> packages) {
        for (String prefix : packages) {
            try {
                return Class.forName(prefix + simpleName);
            } catch (ClassNotFoundException ignored) {
                // Try the next candidate package.
            }
        }
        return null;
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("a method declaring a checked exception in throws")
        void declaredCheckedException() {
            List<Finding> findings = check("""
                    @Transactional
                    public void importOrders() throws IOException {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().ruleId()).isEqualTo("TX003");
            // A warning, not an error: the transaction commits, it does not crash.
            assertThat(findings.getFirst().severity()).isEqualTo(Severity.WARNING);
            assertThat(findings.getFirst().message()).contains("IOException").contains("commits");
            assertThat(findings.getFirst().suggestion()).contains("rollbackFor = IOException.class");
        }

        @Test
        @DisplayName("a method catching a checked exception")
        void caughtCheckedException() {
            assertThat(check("""
                    @Transactional
                    public void importOrders() {
                        try {
                            read();
                        } catch (SQLException e) {
                            log(e);
                        }
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("a multi-catch with one checked exception")
        void multiCatch() {
            assertThat(check("""
                    @Transactional
                    public void importOrders() {
                        try {
                            read();
                        } catch (RuntimeException | SQLException e) {
                            log(e);
                        }
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("several checked exceptions, named once each in the message")
        void severalCheckedExceptions() {
            List<Finding> findings = check("""
                    @Transactional
                    public void importOrders() throws IOException, SQLException {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("IOException").contains("SQLException");
        }

        @Test
        @DisplayName("a public method of a class-level @Transactional")
        void classLevelTransactional() {
            assertThat(RuleTester.check(rule, """
                    @Transactional
                    class Test {
                        public void importOrders() throws IOException {}
                    }
                    """)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("does not report")
    class DoesNotReport {

        @Test
        @DisplayName("rollbackFor already present")
        void rollbackForPresent() {
            assertThat(check("""
                    @Transactional(rollbackFor = IOException.class)
                    public void importOrders() throws IOException {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("rollbackForClass already present")
        void rollbackForClassPresent() {
            assertThat(check("""
                    @Transactional(rollbackForClass = Exception.class)
                    public void importOrders() throws IOException {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a single-member @Transactional still reports, because it names a bean not a policy")
        void singleMemberAnnotation() {
            // @Transactional("orderTxManager") selects a transaction manager. It says nothing about
            // rollback, so the finding stands.
            assertThat(check("""
                    @Transactional("orderTxManager")
                    public void importOrders() throws IOException {}
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("only unchecked exceptions, which already roll back")
        void onlyUnchecked() {
            assertThat(check("""
                    @Transactional
                    public void place() throws IllegalStateException {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("an unchecked exception named by suffix, without being on the list")
        void customUncheckedException() {
            assertThat(check("""
                    @Transactional
                    public void place() throws PaymentDeclinedException {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("no exception handling at all")
        void noExceptions() {
            assertThat(check("""
                    @Transactional
                    public void place() {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a private transactional method, which AOP001 reports as the real cause")
        void privateTransactional() {
            assertThat(check("""
                    @Transactional
                    private void importOrders() throws IOException {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a non-transactional method")
        void notTransactional() {
            assertThat(check("""
                    public void importOrders() throws IOException {}
                    """)).isEmpty();
        }
    }

    @Nested
    @DisplayName("CheckedExceptions classification")
    class Classification {

        @ParameterizedTest
        @ValueSource(strings = {
                "IOException", "java.io.IOException", "SQLException", "Exception", "java.lang.Exception",
                "InterruptedException", "ParseException", "NoSuchMethodException",
        })
        void knownChecked(String typeName) {
            assertThat(CheckedExceptions.isKnownChecked(typeName)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "RuntimeException", "IllegalStateException", "IllegalArgumentException",
                "java.lang.NullPointerException", "MyCustomException", "CustomRuntimeException",
                "AssertionError", "StackOverflowError", "Throwable", "", "   ",
        })
        void notKnownChecked(String typeName) {
            assertThat(CheckedExceptions.isKnownChecked(typeName)).isFalse();
        }

        @Test
        @DisplayName("answers null rather than throwing")
        void toleratesNull() {
            assertThat(CheckedExceptions.isKnownChecked(null)).isFalse();
        }
    }
}
