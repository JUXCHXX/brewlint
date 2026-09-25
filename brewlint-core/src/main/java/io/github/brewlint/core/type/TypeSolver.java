package io.github.brewlint.core.type;

/**
 * Answers "is a value declared as this type assignable to that type?".
 *
 * <p><strong>Why this is an interface.</strong> Rules must be written against this contract and
 * never against a specific resolution strategy. Hito 1 ships
 * {@link io.github.brewlint.core.type.SyntacticTypeSolver}, which compares the type name as it is
 * written in the source plus a table of known JDK hierarchies. It needs no classpath and starts
 * instantly, at the cost of missing types it does not know about. A later milestone can swap in a
 * full {@code JavaSymbolSolver} without touching a single rule.
 *
 * <p>Both names may be fully qualified or simple. Implementations must handle either.
 */
public interface TypeSolver {

    /**
     * @param declaredTypeName the type as written in the source, e.g. {@code FileInputStream},
     *                         {@code java.io.FileInputStream}, {@code List<String>}, {@code byte[]}
     * @param targetTypeName   the type to test against, e.g. {@code java.io.InputStream}
     * @return true if a value of {@code declaredTypeName} could be assigned to
     *         {@code targetTypeName}
     */
    boolean isA(String declaredTypeName, String targetTypeName);

    /**
     * Convenience for the common "is this one of these resource types" question.
     *
     * @return true if the declared type is assignable to any of the candidates
     */
    default boolean isAnyOf(String declaredTypeName, String... targetTypeNames) {
        for (String targetTypeName : targetTypeNames) {
            if (isA(declaredTypeName, targetTypeName)) {
                return true;
            }
        }
        return false;
    }
}
