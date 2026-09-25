package io.github.brewlint.core.rules.bean;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BEAN003: field injection instead of constructor injection")
class FieldInjectionRuleTest {

    private final FieldInjectionRule rule = new FieldInjectionRule();

    private List<Finding> check(String source) {
        return RuleTester.check(rule, source);
    }

    @Test
    @DisplayName("is INFO, because this is a convention and not a defect")
    void defaultSeverityIsInfo() {
        assertThat(rule.defaultSeverity())
                .as("nothing breaks today; severity must not overstate it")
                .isEqualTo(Severity.INFO);
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("@Autowired on a private field")
        void autowiredField() {
            List<Finding> findings = check("""
                    @Service
                    class OrderService {
                        @Autowired
                        private PaymentGateway gateway;
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().ruleId()).isEqualTo("BEAN003");
            assertThat(findings.getFirst().message())
                    .contains("gateway")
                    .contains("null between construction and injection");
            assertThat(findings.getFirst().suggestion()).contains("constructor");
        }

        @Test
        @DisplayName("@Inject and @Resource are injection points too")
        void otherInjectionAnnotations() {
            assertThat(check("""
                    @Service
                    class OrderService {
                        @Inject
                        private PaymentGateway gateway;
                    }
                    """)).hasSize(1);

            assertThat(check("""
                    @Service
                    class OrderService {
                        @Resource
                        private PaymentGateway gateway;
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("a package-private field, which is not only about private")
        void packagePrivateField() {
            assertThat(check("""
                    @Service
                    class OrderService {
                        @Autowired
                        PaymentGateway gateway;
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("several injected fields in one class")
        void severalFields() {
            assertThat(check("""
                    @Service
                    class OrderService {
                        @Autowired
                        private PaymentGateway gateway;
                        @Autowired
                        private AuditLog auditLog;
                    }
                    """)).hasSize(2);
        }
    }

    @Nested
    @DisplayName("does not report")
    class DoesNotReport {

        @Test
        @DisplayName("constructor injection, which is the recommended form")
        void constructorInjection() {
            assertThat(check("""
                    @Service
                    class OrderService {
                        private final PaymentGateway gateway;
                        public OrderService(PaymentGateway gateway) {
                            this.gateway = gateway;
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a final field with no annotation, wired by the single constructor")
        void finalFieldNoAnnotation() {
            assertThat(check("""
                    @Service
                    class OrderService {
                        private final PaymentGateway gateway;
                        public OrderService(PaymentGateway gateway) {
                            this.gateway = gateway;
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("setter injection, which the rule does not claim to be about")
        void setterInjection() {
            assertThat(check("""
                    @Service
                    class OrderService {
                        private PaymentGateway gateway;
                        @Autowired
                        public void setGateway(PaymentGateway gateway) {
                            this.gateway = gateway;
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("an ordinary field with no injection annotation")
        void plainField() {
            assertThat(check("""
                    @Service
                    class OrderService {
                        private String name = "orders";
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a field annotated with something else entirely")
        void unrelatedAnnotation() {
            assertThat(check("""
                    @Service
                    class OrderService {
                        @Value("${orders.timeout}")
                        private long timeout;
                    }
                    """)).isEmpty();
        }
    }
}
