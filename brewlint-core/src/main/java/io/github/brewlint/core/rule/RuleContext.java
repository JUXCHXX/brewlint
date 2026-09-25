package io.github.brewlint.core.rule;

import com.github.javaparser.ast.CompilationUnit;
import io.github.brewlint.core.type.TypeSolver;

import java.nio.file.Path;

/**
 * Read-only view of the file a rule is currently analysing.
 *
 * <p>Deliberately narrow. A rule that needs more context (the whole project, the resolved type of
 * a field, a value from configuration) is a sign the rule is trying to do too much; add the
 * capability to this interface explicitly rather than leaking the engine in.
 */
public interface RuleContext {

    /** The parsed file. */
    CompilationUnit compilationUnit();

    /** Absolute or input-relative path of the file on disk. */
    Path file();

    /**
     * Path as it should appear in reports, relative to the project root.
     *
     * <p>Machine-independent: always forward slashes, so the same scan produces the same output on
     * Windows and Linux. A finding that names an absolute path is useless in a CI log.
     */
    String relativePath();

    /** Type resolution for this run. See {@link TypeSolver} for why this is an interface. */
    TypeSolver typeSolver();
}
