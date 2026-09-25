package io.github.brewlint.core.config;

import io.github.brewlint.core.model.Severity;

import java.util.Optional;

/**
 * Per-rule configuration, the parsed form of one entry under {@code rules:} in {@code brewlint.yml}.
 *
 * @param enabled           false to switch the rule off entirely
 * @param severityOverride  replaces the rule's own default severity, or empty to keep the default
 */
public record RuleSettings(boolean enabled, Optional<Severity> severityOverride) {

    public static RuleSettings on() {
        return new RuleSettings(true, Optional.empty());
    }

    public static RuleSettings off() {
        return new RuleSettings(false, Optional.empty());
    }

    public static RuleSettings withSeverity(Severity severity) {
        return new RuleSettings(true, Optional.of(severity));
    }
}
