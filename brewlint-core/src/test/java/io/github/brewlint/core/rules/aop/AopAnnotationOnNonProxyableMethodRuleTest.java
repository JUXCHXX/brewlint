package io.github.brewlint.core.rules.aop;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AOP001: proxy-dependent annotation on a method Spring cannot proxy")
class AopAnnotationOnNonProxyableMethodRuleTest {

    private final AopAnnotationOnNonProxyableMethodRule rule = new AopAnnotationOnNonProxyableMethodRule();

    private List<Finding> check(String classBody) {
        return RuleTester.checkInClass(rule, classBody);
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("private method annotated @Transactional")
        void privateTransactional() {
            List<Finding> findings = check("""
                    @Transactional
                    private void charge() {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().ruleId()).isEqualTo("AOP001");
            assertThat(findings.getFirst().severity()).isEqualTo(Severity.ERROR);
            assertThat(findings.getFirst().message())
                    .contains("@Transactional")
                    .contains("private")
                    .contains("transaction is never started or committed");
        }

        @Test
        @DisplayName("private method annotated @Async, with the async-specific consequence")
        void privateAsync() {
            List<Finding> findings = check("""
                    @Async
                    private void send() {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("runs synchronously on the caller thread");
        }

        @Test
        @DisplayName("final method annotated @Cacheable")
        void finalCacheable() {
            List<Finding> findings = check("""
                    @Cacheable("users")
                    public final User load(long id) { return null; }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("final");
        }

        @Test
        @DisplayName("static method annotated @Scheduled")
        void staticScheduled() {
            List<Finding> findings = check("""
                    @Scheduled(cron = "0 0 * * * *")
                    public static void purge() {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("static").contains("never scheduled");
        }

        @Test
        @DisplayName("method that is both private and static names both blockers")
        void privateAndStatic() {
            List<Finding> findings = check("""
                    @Transactional
                    private static void transfer() {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("private static");
        }

        @Test
        @DisplayName("fully qualified annotation is still recognised")
        void fullyQualifiedAnnotation() {
            List<Finding> findings = check("""
                    @org.springframework.transaction.annotation.Transactional
                    private void charge() {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("@Transactional");
        }

        @Test
        @DisplayName("several offending methods in one class, ignoring the correct ones")
        void severalMethods() {
            List<Finding> findings = check("""
                    @Transactional
                    private void a() {}
                    @Async
                    public void b() {}
                    @Cacheable
                    private final void c() {}
                    @Scheduled(fixedDelay = 1000)
                    public static void d() {}
                    """);

            assertThat(findings).extracting(Finding::line).hasSize(3);
        }
    }

    @Nested
    @DisplayName("does not report")
    class DoesNotReport {

        @Test
        @DisplayName("public, non-final, non-static method: CGLIB can override it")
        void publicMethod() {
            assertThat(check("""
                    @Transactional
                    public void charge() {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("package-private method: a CGLIB subclass in the same package can override it")
        void packagePrivateMethod() {
            assertThat(check("""
                    @Transactional
                    void charge() {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("protected method")
        void protectedMethod() {
            assertThat(check("""
                    @Transactional
                    protected void charge() {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("private method with an annotation Spring does not proxy")
        void privateMethodWithUnrelatedAnnotation() {
            assertThat(check("""
                    @Deprecated
                    @Override
                    private void charge() {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("private method with no annotation at all")
        void privateMethodWithoutAnnotation() {
            assertThat(check("""
                    private void charge() {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("annotation on the class instead of a non-proxyable method")
        void classLevelAnnotation() {
            List<Finding> findings = RuleTester.check(rule, """
                    @Transactional
                    class Test {
                        public void charge() {}
                    }
                    """);

            assertThat(findings).isEmpty();
        }

        @Test
        @DisplayName("class with no annotations and no methods")
        void emptyClass() {
            assertThat(check("int field = 1;")).isEmpty();
        }
    }
}
