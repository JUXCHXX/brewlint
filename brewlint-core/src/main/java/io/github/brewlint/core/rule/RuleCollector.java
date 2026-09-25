package io.github.brewlint.core.rule;

import com.github.javaparser.ast.Node;

/**
 * Sink a rule reports findings into.
 *
 * <p>The rule does not supply an id, a category or a severity: the engine stamps those, which keeps
 * every finding consistent and makes {@code brewlint.yml} severity overrides apply in exactly one
 * place.
 */
public interface RuleCollector {

    /** Reports a finding anchored at {@code node}'s source range. */
    void report(Node node, String message, String suggestion);

    /** Reports a finding at an explicit position, for cases with no single enclosing node. */
    void reportAt(int line, int column, int endLine, String message, String suggestion);
}
