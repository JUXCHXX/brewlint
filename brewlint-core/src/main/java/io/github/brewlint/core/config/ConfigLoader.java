package io.github.brewlint.core.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads {@code brewlint.yml}.
 *
 * <p>Uses SnakeYAML's {@link SafeConstructor}, so a configuration file can never instantiate
 * arbitrary classes. Configuration is untrusted input: a repository that a pull request can edit
 * gets its {@code brewlint.yml} parsed by this code.
 */
public final class ConfigLoader {

    private static final String DEFAULT_FILE_NAME = "brewlint.yml";

    private ConfigLoader() {
    }

    /**
     * Loads {@code brewlint.yml} from {@code projectRoot}.
     *
     * <p>A missing file is not an error: it means "all defaults", which is what makes Brewlint
     * useful on a project it has never seen before.
     *
     * @throws IllegalArgumentException if the file is present but malformed
     */
    public static BrewlintConfig load(Path projectRoot) {
        Path configFile = projectRoot.resolve(DEFAULT_FILE_NAME);
        if (!Files.isRegularFile(configFile)) {
            return BrewlintConfig.defaults();
        }
        try (InputStream input = Files.newInputStream(configFile)) {
            return parse(input);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not read " + configFile, exception);
        }
    }

    @SuppressWarnings("unchecked")
    static BrewlintConfig parse(InputStream input) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded = new Yaml(new SafeConstructor(options)).load(input);

        if (loaded == null) {
            return BrewlintConfig.defaults();
        }
        if (!(loaded instanceof Map<?, ?> root)) {
            throw new IllegalArgumentException("brewlint.yml must contain a mapping at the top level");
        }

        Map<String, RuleSettings> rules = new LinkedHashMap<>();
        Object rawRules = root.get("rules");
        if (rawRules != null) {
            if (!(rawRules instanceof Map<?, ?> rawRuleMap)) {
                throw new IllegalArgumentException("'rules' in brewlint.yml must be a mapping");
            }
            for (Map.Entry<?, ?> entry : rawRuleMap.entrySet()) {
                String ruleId = String.valueOf(entry.getKey());
                rules.put(ruleId, parseRuleSettings(ruleId, entry.getValue()));
            }
        }

        List<String> exclude = new ArrayList<>();
        Object rawExclude = root.get("exclude");
        if (rawExclude != null) {
            if (!(rawExclude instanceof List<?> rawExcludeList)) {
                throw new IllegalArgumentException("'exclude' in brewlint.yml must be a list of glob patterns");
            }
            for (Object pattern : rawExcludeList) {
                exclude.add(String.valueOf(pattern));
            }
        }

        return new BrewlintConfig(rules, exclude);
    }

    private static RuleSettings parseRuleSettings(String ruleId, Object raw) {
        if (raw == null) {
            return RuleSettings.off();
        }
        if (raw instanceof Boolean enabled) {
            return enabled ? RuleSettings.on() : RuleSettings.off();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "Rule '" + ruleId + "' in brewlint.yml must be a boolean or a mapping");
        }
        Object rawEnabled = map.get("enabled");
        boolean enabled = !(rawEnabled instanceof Boolean value) || value;
        Object rawSeverity = map.get("severity");
        if (rawSeverity == null) {
            return new RuleSettings(enabled, java.util.Optional.empty());
        }
        try {
            return new RuleSettings(enabled, java.util.Optional.of(
                    io.github.brewlint.core.model.Severity.parse(String.valueOf(rawSeverity))));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Rule '" + ruleId + "': " + exception.getMessage(), exception);
        }
    }
}
