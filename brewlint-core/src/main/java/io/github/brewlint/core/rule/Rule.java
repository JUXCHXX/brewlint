package io.github.brewlint.core.rule;

import io.github.brewlint.core.model.Severity;

/**
 * A single analysis check.
 *
 * <p>Every rule is an independent class with no knowledge of the engine, the CLI or the report
 * format. It receives a parsed compilation unit and reports problems through a
 * {@link RuleCollector}.
 *
 * <h2>Contract for implementors</h2>
 * <ul>
 *   <li>Return a stable {@link #id()}. Ids appear in {@code brewlint.yml}, in CI baselines and in
 *       the GitHub Action, so renaming one is a breaking change.</li>
 *   <li>Report through {@link RuleCollector}, never construct a {@link io.github.brewlint.core.model.Finding}
 *       directly. The engine stamps the id, category and effective severity, so a rule cannot
 *       misreport its own identity and configuration overrides apply in one place.</li>
 *   <li>Report only what you are certain about. A false positive costs a user more trust than a
 *       missed issue costs them time. If in doubt, do not report.</li>
 *   <li>Write the suggestion as the fix, not as a description of the problem. The message says
 *       what is wrong; the suggestion says what to type.</li>
 * </ul>
 *
 * <p>Implementations must be stateless and thread-safe: the engine may run them concurrently over
 * many files.
 */
public interface Rule {

    /** Stable identifier, e.g. {@code TX001}. Uppercase letters followed by digits. */
    String id();

    /** Rule family, e.g. {@code transactional}. Used for grouping in reports. */
    String category();

    /** Severity used when {@code brewlint.yml} does not override it. */
    Severity defaultSeverity();

    /**
     * Whether this rule needs a {@link io.github.brewlint.core.project.ProjectIndex}.
     *
     * <p>Default {@code false}, which is right for nearly every rule. Returning {@code true} makes
     * the engine parse the sources an extra time, so a rule must only ask when it genuinely
     * compares something across files. "A prototype bean injected into a singleton" is such a case;
     * "this method is private" is not.
     */
    default boolean requiresProjectIndex() {
        return false;
    }

    /**
     * Inspects one compilation unit and reports whatever it finds.
     *
     * <p>Must not throw for ordinary source constructs. A rule that throws is disabled for the
     * rest of the run and recorded in the parse-error count, so that one broken rule cannot abort
     * a whole scan.
     *
     * @param context the file being analysed
     * @param out     sink for findings; safe to call zero or more times
     */
    void analyze(RuleContext context, RuleCollector out);
}
