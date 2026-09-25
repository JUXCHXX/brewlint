package io.github.brewlint.core.rules.transactional;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TX002: @Transactional method called from inside its own class")
class SelfInvokedTransactionalMethodRuleTest {

    private final SelfInvokedTransactionalMethodRule rule = new SelfInvokedTransactionalMethodRule();

    private List<Finding> check(String classBody) {
        return RuleTester.checkInClass(rule, classBody);
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("an unqualified call to a transactional method")
        void unqualifiedCall() {
            List<Finding> findings = check("""
                    @Transactional
                    public void place() {}
                    public void placeOrder() {
                        place();
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().ruleId()).isEqualTo("TX002");
            assertThat(findings.getFirst().severity()).isEqualTo(Severity.ERROR);
            assertThat(findings.getFirst().message())
                    .contains("bypasses the Spring proxy")
                    .contains("without a transaction");
            // The finding belongs on the call, not on the declaration, so the developer sees where
            // the mistake is made.
            assertThat(findings.getFirst().line()).isGreaterThan(4);
        }

        @Test
        @DisplayName("an explicit this.place() call")
        void explicitThisCall() {
            assertThat(check("""
                    @Transactional
                    public void place() {}
                    public void placeOrder() {
                        this.place();
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("a method inherited from a class-level @Transactional")
        void classLevelTransactional() {
            List<Finding> findings = RuleTester.check(rule, """
                    @Transactional
                    class Test {
                        public void place() {}
                        public void placeOrder() {
                            place();
                        }
                    }
                    """);

            assertThat(findings).hasSize(1);
        }

        @Test
        @DisplayName("readOnly transactions too")
        void readOnlyTransaction() {
            assertThat(check("""
                    @Transactional(readOnly = true)
                    public void audit() {}
                    public void run() {
                        audit();
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("recursive self-invocation")
        void recursion() {
            assertThat(check("""
                    @Transactional
                    public void retry() {
                        retry();
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("several self-invocations in one class")
        void severalCalls() {
            assertThat(check("""
                    @Transactional
                    public void a() {}
                    @Transactional
                    public void b() {}
                    public void c() {
                        a();
                        this.b();
                    }
                    """)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("does not report")
    class DoesNotReport {

        @Test
        @DisplayName("a call through another bean, which is the fix")
        void callThroughAnotherBean() {
            assertThat(check("""
                    @Transactional
                    public void place() {}
                    public void placeOrder() {
                        paymentService.place();
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a call to a method that is not transactional")
        void nonTransactionalTarget() {
            assertThat(check("""
                    @Transactional
                    public void place() {}
                    public void helper() {}
                    public void placeOrder() {
                        helper();
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a private transactional method, which AOP001 already reports as the real cause")
        void privateTransactionalTarget() {
            assertThat(check("""
                    @Transactional
                    private void place() {}
                    public void placeOrder() {
                        place();
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a static transactional method, same reasoning")
        void staticTransactionalTarget() {
            assertThat(check("""
                    @Transactional
                    public static void place() {}
                    public void placeOrder() {
                        place();
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("an overload whose arity does not match the transactional one")
        void overloadArityDiffers() {
            // charge() is not transactional; only charge(int) is. The bare call() resolves to the
            // non-transactional overload, so there is no advice being bypassed.
            assertThat(check("""
                    @Transactional
                    public void charge(int amount) {}
                    public void charge() {}
                    public void run() {
                        charge();
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("an overload that does match")
        void overloadArityMatches() {
            assertThat(check("""
                    @Transactional
                    public void charge(int amount) {}
                    public void charge() {
                        charge(100);
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("a class with no transactions at all")
        void noTransactions() {
            assertThat(check("""
                    public void a() {}
                    public void b() {
                        a();
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a static call to a static method")
        void staticCall() {
            assertThat(check("""
                    @Transactional
                    public static void place() {}
                    public void placeOrder() {
                        Test.place();
                    }
                    """)).isEmpty();
        }
    }
}
