package io.github.brewlint.core.engine;

import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("RuleRegistry: ServiceLoader discovery")
class RuleRegistryTest {

    @Test
    @DisplayName("finds the rules declared in META-INF/services")
    void discoversShippedRules() {
        List<Rule> rules = RuleRegistry.discover();

        assertThat(rules).extracting(Rule::id).containsExactly("AOP001", "RES001");
    }

    @Test
    @DisplayName("returns them sorted by id, so output is deterministic")
    void sortsById() {
        assertThat(RuleRegistry.discover()).isSortedAccordingTo((left, right) -> left.id().compareTo(right.id()));
    }

    @Test
    @DisplayName("two rules sharing an id is a packaging bug and fails fast")
    void rejectsDuplicateIds() {
        assertThatThrownBy(() -> RuleRegistry.validate(List.of(new StubRule("AOP001"), new StubRule("AOP001"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AOP001")
                .hasMessageContaining("unique");
    }

    @Test
    @DisplayName("an empty set of rules is valid")
    void acceptsNoRules() {
        assertThat(RuleRegistry.validate(new ArrayList<>())).isEmpty();
    }

    private record StubRule(String id) implements Rule {
        @Override
        public String category() {
            return "test";
        }

        @Override
        public Severity defaultSeverity() {
            return Severity.INFO;
        }

        @Override
        public void analyze(RuleContext context, RuleCollector out) {
            // no-op
        }
    }
}
