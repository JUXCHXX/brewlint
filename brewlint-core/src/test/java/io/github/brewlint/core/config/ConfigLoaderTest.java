package io.github.brewlint.core.config;

import io.github.brewlint.core.model.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("brewlint.yml")
class ConfigLoaderTest {

    private static BrewlintConfig parse(String yaml) {
        return ConfigLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("an empty file means every default")
    void emptyFile() {
        BrewlintConfig config = parse("");

        assertThat(config.rules()).isEmpty();
        assertThat(config.exclude()).isEmpty();
        assertThat(config.isEnabled("AOP001")).isTrue();
    }

    @Test
    @DisplayName("a boolean toggles a rule")
    void booleanToggle() {
        BrewlintConfig config = parse("""
                rules:
                  AOP001: false
                  RES001: true
                """);

        assertThat(config.isEnabled("AOP001")).isFalse();
        assertThat(config.isEnabled("RES001")).isTrue();
    }

    @Test
    @DisplayName("a rule key with no value disables it")
    void implicitDisable() {
        assertThat(parse("""
                rules:
                  AOP001:
                """).isEnabled("AOP001")).isFalse();
    }

    @Test
    @DisplayName("a mapping sets enabled and severity together")
    void enabledAndSeverity() {
        BrewlintConfig config = parse("""
                rules:
                  RES001:
                    enabled: true
                    severity: warning
                """);

        assertThat(config.isEnabled("RES001")).isTrue();
        assertThat(config.settingsFor("RES001").orElseThrow().severityOverride())
                .contains(Severity.WARNING);
    }

    @Test
    @DisplayName("a disabled rule keeps its severity setting")
    void disabledWithSeverity() {
        assertThat(parse("""
                rules:
                  RES001:
                    enabled: false
                    severity: INFO
                """).settingsFor("RES001").orElseThrow().severityOverride())
                .contains(Severity.INFO);
    }

    @Test
    @DisplayName("exclude takes a list of globs")
    void excludeGlobs() {
        assertThat(parse("""
                exclude:
                  - "**/generated/**"
                  - "**/Legacy*.java"
                """).exclude())
                .containsExactly("**/generated/**", "**/Legacy*.java");
    }

    @Test
    @DisplayName("a missing file is not an error, it just means defaults")
    void missingFile(@TempDir Path projectRoot) {
        assertThat(ConfigLoader.load(projectRoot)).isEqualTo(BrewlintConfig.defaults());
    }

    @Test
    @DisplayName("loads from disk")
    void loadsFromDisk(@TempDir Path projectRoot) throws Exception {
        Files.writeString(projectRoot.resolve("brewlint.yml"), """
                rules:
                  AOP001: false
                exclude:
                  - "**/generated/**"
                """);

        BrewlintConfig config = ConfigLoader.load(projectRoot);

        assertThat(config.isEnabled("AOP001")).isFalse();
        assertThat(config.exclude()).containsExactly("**/generated/**");
    }

    @Test
    @DisplayName("a misspelled severity fails loudly instead of being ignored")
    void rejectsUnknownSeverity() {
        assertThatThrownBy(() -> parse("""
                rules:
                  RES001:
                    severity: CRITICAL
                """))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CRITICAL")
                .hasMessageContaining("ERROR, WARNING, INFO");
    }

    @Test
    @DisplayName("a non-mapping document is rejected")
    void rejectsNonMapping() {
        assertThatThrownBy(() -> parse("- just\n- a list\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mapping");
    }

    @Test
    @DisplayName("duplicate keys are rejected rather than silently merged")
    void rejectsDuplicateKeys() {
        assertThatThrownBy(() -> parse("""
                rules:
                  AOP001: false
                  AOP001: true
                """))
                .isInstanceOf(org.yaml.snakeyaml.constructor.DuplicateKeyException.class);
    }

    @Test
    @DisplayName("configuration is untrusted input, so arbitrary types are not instantiated")
    void doesNotInstantiateArbitraryTypes() {
        assertThatThrownBy(() -> parse("!!java.net.URL [http://example.com]"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("validateAgainst lists the available rules when one is unknown")
    void validateAgainstReportsAvailableRules() {
        assertThatThrownBy(() -> new BrewlintConfig(Map.of("NOPE", RuleSettings.off()), java.util.List.of())
                .validateAgainst(java.util.List.of(new FakeRule())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("NOPE")
                .hasMessageContaining("FAKE001");
    }

    private record FakeRule() implements io.github.brewlint.core.rule.Rule {
        @Override
        public String id() {
            return "FAKE001";
        }

        @Override
        public String category() {
            return "test";
        }

        @Override
        public Severity defaultSeverity() {
            return Severity.INFO;
        }

        @Override
        public void analyze(io.github.brewlint.core.rule.RuleContext context,
                            io.github.brewlint.core.rule.RuleCollector out) {
            // no-op
        }
    }
}
