package io.github.brewlint.core.engine;

import io.github.brewlint.core.rule.Rule;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Discovers rules through {@link ServiceLoader}.
 *
 * <p>Adding a rule means adding a class and one line to
 * {@code META-INF/services/io.github.brewlint.core.rule.Rule}. No engine change, no registry
 * edit, no {@code @Component} scan. That is the whole point of the plugin contract: a rule stays
 * an independent class.
 *
 * <p>Rules are sorted by id so that output is deterministic. Two rules sharing an id is a
 * packaging bug and fails fast, because it would otherwise make findings ambiguous.
 */
public final class RuleRegistry {

    private RuleRegistry() {
    }

    /** Loads every rule visible on the classpath, sorted by id. */
    public static List<Rule> discover() {
        // ServiceLoader is Iterable, not Collection, so it has to be drained explicitly.
        List<Rule> discovered = new ArrayList<>();
        for (Rule rule : ServiceLoader.load(Rule.class)) {
            discovered.add(rule);
        }
        return validate(discovered);
    }

    /**
     * Sorts by id and rejects duplicate ids.
     *
     * @throws IllegalStateException if two rules share an id
     */
    public static List<Rule> validate(List<Rule> rules) {
        Set<String> seen = new LinkedHashSet<>();
        for (Rule rule : rules) {
            if (!seen.add(rule.id())) {
                throw new IllegalStateException(
                        "Duplicate rule id '" + rule.id() + "' from " + rule.getClass().getName()
                                + ". Rule ids are part of the public contract and must be unique.");
            }
        }
        List<Rule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparing(Rule::id));
        return List.copyOf(sorted);
    }
}
