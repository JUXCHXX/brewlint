package io.github.brewlint.core.rules.bean;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("BEAN001: persistence entity annotated as a Spring bean")
class EntityAnnotatedAsBeanRuleTest {

    private final EntityAnnotatedAsBeanRule rule = new EntityAnnotatedAsBeanRule();

    private List<Finding> check(String source) {
        return RuleTester.check(rule, source);
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("@Entity plus @Component")
        void entityAndComponent() {
            List<Finding> findings = check("""
                    @Entity
                    @Component
                    class Order {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().ruleId()).isEqualTo("BEAN001");
            assertThat(findings.getFirst().severity()).isEqualTo(Severity.ERROR);
            assertThat(findings.getFirst().message())
                    .contains("@Entity")
                    .contains("@Component")
                    .contains("second, unmanaged instance");
        }

        @Test
        @DisplayName("@Entity plus @Service")
        void entityAndService() {
            assertThat(check("""
                    @Entity
                    @Service
                    class Order {}
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("Mongo @Document, which fails the same way")
        void documentAndRepository() {
            List<Finding> findings = check("""
                    @Document(collection = "orders")
                    @Repository
                    class Order {}
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("@Document").contains("@Repository");
        }

        @Test
        @DisplayName("annotation order in the source does not matter")
        void reversedAnnotationOrder() {
            assertThat(check("""
                    @Component
                    @Entity
                    class Order {}
                    """)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("does not report")
    class DoesNotReport {

        @Test
        @DisplayName("a plain entity with no stereotype")
        void plainEntity() {
            assertThat(check("""
                    @Entity
                    @Table(name = "orders")
                    class Order {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a plain service with no persistence annotation")
        void plainService() {
            assertThat(check("""
                    @Service
                    class OrderService {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a stereotype on a class that is not an entity")
        void stereotypeOnly() {
            assertThat(check("""
                    @Service
                    @Transactional
                    class OrderService {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a @Component interface, which is a legitimate configuration pattern")
        void componentInterface() {
            assertThat(check("""
                    @Component
                    interface OrderGateway {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a base class carrying @MappedSuperclass, which is not a managed instance")
        void mappedSuperclass() {
            assertThat(check("""
                    @MappedSuperclass
                    @Component
                    abstract class Audited {}
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a project class named Entity but not annotated")
        void similarName() {
            assertThat(check("""
                    @Component
                    class EntityView {}
                    """)).isEmpty();
        }
    }

    @Test
    @DisplayName("the documented persistence annotations match what the rule checks")
    void documentedAnnotations() {
        assertThat(EntityAnnotatedAsBeanRule.persistenceAnnotations())
                .containsExactly("Document", "Entity");
    }
}
