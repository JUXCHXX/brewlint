package io.github.brewlint.core.config;

import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.Rule;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Effective configuration for one run: which rules are on, how loud they are, and what is out of
 * scope.
 *
 * <p>Immutable. Missing entries mean "use the rule's own default", so adding a new rule never
 * requires touching an existing {@code brewlint.yml}.
 */
public record BrewlintConfig(Map<String, RuleSettings> rules, List<String> exclude) {

    private static final BrewlintConfig DEFAULTS = new BrewlintConfig(Map.of(), List.of());

    public BrewlintConfig {
        rules = Map.copyOf(rules);
        exclude = List.copyOf(exclude);
    }

    /** Everything on, nothing excluded, every rule at its own default severity. */
    public static BrewlintConfig defaults() {
        return DEFAULTS;
    }

    public Optional<RuleSettings> settingsFor(String ruleId) {
        return Optional.ofNullable(rules.get(ruleId));
    }

    public boolean isEnabled(String ruleId) {
        return settingsFor(ruleId).map(RuleSettings::enabled).orElse(Boolean.TRUE);
    }

    /** The rule's own default, unless {@code brewlint.yml} overrides it. */
    public Severity severityFor(Rule rule) {
        return settingsFor(rule.id())
                .flatMap(RuleSettings::severityOverride)
                .orElseGet(rule::defaultSeverity);
    }

    public List<String> exclude() {
        return exclude;
    }

    /**
     * Fails fast on rule ids that no loaded rule provides. A typo in {@code brewlint.yml} would
     * otherwise silently leave a rule running with the wrong severity.
     */
    public void validateAgainst(List<Rule> loadedRules) {
        List<String> knownIds = loadedRules.stream().map(Rule::id).toList();
        List<String> unknown = rules.keySet().stream()
                .filter(ruleId -> !knownIds.contains(ruleId))
                .sorted()
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException(
                    "brewlint.yml references rules that do not exist: " + String.join(", ", unknown)
                            + ". Available rules: " + String.join(", ", knownIds));
        }
    }
}
