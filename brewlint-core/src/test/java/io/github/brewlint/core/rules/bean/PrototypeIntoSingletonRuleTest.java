package io.github.brewlint.core.rules.bean;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BEAN002: prototype-scoped bean injected into a singleton")
class PrototypeIntoSingletonRuleTest {

    private final PrototypeIntoSingletonRule rule = new PrototypeIntoSingletonRule();

    @Test
    @DisplayName("declares that it needs the project index, so the engine builds one")
    void requiresProjectIndex() {
        assertThat(rule.requiresProjectIndex())
                .as("this rule compares across files and must say so")
                .isTrue();
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("prototype field injected into a singleton, across two files")
        void prototypeFieldInSingleton() {
            List<Finding> findings = RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        @Autowired
                        private AuditLog auditLog;
                    }
                    """,
                    """
                    @Component
                    @Scope("prototype")
                    class AuditLog {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().ruleId()).isEqualTo("BEAN002");
            assertThat(findings.getFirst().severity()).isEqualTo(Severity.WARNING);
            assertThat(findings.getFirst().message())
                    .contains("AuditLog")
                    .contains("prototype")
                    .contains("shares the one instance");
            assertThat(findings.getFirst().suggestion()).contains("ObjectProvider<AuditLog>");
        }

        @Test
        @DisplayName("scope declared on the stereotype instead of @Scope")
        void scopeOnStereotype() {
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        @Autowired
                        private AuditLog auditLog;
                    }
                    """,
                    """
                    @Component(scope = "prototype")
                    class AuditLog {}
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("prototype constructor parameter into a singleton")
        void prototypeConstructorParameter() {
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        private final AuditLog auditLog;
                        @Autowired
                        public OrderService(AuditLog auditLog) {
                            this.auditLog = auditLog;
                        }
                    }
                    """,
                    """
                    @Component
                    @Scope("prototype")
                    class AuditLog {}
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("@Inject and @Resource count as injection points too")
        void otherInjectionAnnotations() {
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        @Inject
                        private AuditLog auditLog;
                    }
                    """,
                    """
                    @Component
                    @Scope("prototype")
                    class AuditLog {}
                    """)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("does not report")
    class DoesNotReport {

        @Test
        @DisplayName("a singleton bean injected into a singleton, which is the normal case")
        void singletonIntoSingleton() {
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        @Autowired
                        private PaymentGateway gateway;
                    }
                    """,
                    """
                    @Component
                    class PaymentGateway {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a prototype field that is not injected, so nothing shares it")
        void prototypeWithoutInjection() {
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        private AuditLog auditLog = new AuditLog();
                    }
                    """,
                    """
                    @Component
                    @Scope("prototype")
                    class AuditLog {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a consumer that is not a Spring bean at all")
        void consumerIsNotABean() {
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    class OrderService {
                        @Autowired
                        private AuditLog auditLog;
                    }
                    """,
                    """
                    @Component
                    @Scope("prototype")
                    class AuditLog {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a type that does not exist anywhere in the scanned sources")
        void unknownType() {
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        @Autowired
                        private FromALibrary auditLog;
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("two types share a simple name and only one is a prototype")
        void ambiguousSimpleName() {
            // The honest answer is "I do not know which Order this is", so the rule stays quiet.
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        @Autowired
                        private Order lastOrder;
                    }
                    """,
                    """
                    package com.a;
                    @Component
                    @Scope("prototype")
                    class Order {}
                    """,
                    """
                    package com.b;
                    @Component
                    class Order {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a fully qualified field type, which cannot be confused")
        void fullyQualifiedFieldType() {
            assertThat(RuleTester.checkWithProject(rule,
                    """
                    @Service
                    class OrderService {
                        @Autowired
                        private com.a.AuditLog auditLog;
                    }
                    """,
                    """
                    package com.a;
                    @Component
                    @Scope("prototype")
                    class AuditLog {}
                    """)).hasSize(1);
        }
    }
}
