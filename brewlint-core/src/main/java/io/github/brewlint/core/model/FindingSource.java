package io.github.brewlint.core.model;

/**
 * Where a finding came from.
 *
 * <p>This exists so a reader can tell proven from guessed. A finding produced by a rule was derived
 * from the syntax tree, and a developer can check the reasoning themselves. A finding produced by a
 * language model is a guess, and a linter that mixes the two without saying which is which is not
 * being straight with the person reading the report.
 *
 * <p>It is also a severity ceiling in practice. A rule can justify an ERROR because it decided
 * something. A model cannot, so an AI finding never outranks the rules.
 */
public enum FindingSource {

    /** Derived from the syntax tree by a rule. Reproducible, and the reasoning is in the message. */
    RULE,

    /** Suggested by a language model. Unverified, and labelled as such wherever it is shown. */
    AI
}
