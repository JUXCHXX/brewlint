package io.github.brewlint.core.rules.resource;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * RES002: a stream returned by {@code java.nio.file.Files} is never closed.
 *
 * <p>{@code Files.lines}, {@code Files.list}, {@code Files.walk} and {@code Files.find} all return
 * a {@code Stream} that holds an open directory handle and an open file handle. Consuming the
 * stream is not the same as closing it, and neither is chaining a terminal operation onto it. The
 * Oracle javadoc says so explicitly for {@code Files.lines}: "the stream must be closed... failure
 * to do so may result in file descriptors being leaked."
 *
 * <p>This is the trap {@link UnclosedResourceRule} structurally cannot catch. RES001 looks at the
 * declared type, and a {@code Stream} is not a resource type, because most streams are cheap in
 * memory. These are the ones that are not.
 *
 * <h2>Conservative by construction</h2>
 * Only a variable assigned directly from one of those four calls is reported. A call result that
 * flows into a method argument, or is consumed and discarded, is left alone, because concluding
 * anything about it needs dataflow analysis this rule does not do.
 */
public final class UnclosedFileStreamRule implements Rule {

    /** The {@code Files} methods whose returned stream holds an OS handle. */
    private static final Set<String> FILE_STREAM_METHODS = Set.of("lines", "list", "walk", "find");

    @Override
    public String id() {
        return "RES002";
    }

    @Override
    public String category() {
        return "resource";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public void analyze(RuleContext context, RuleCollector out) {
        for (VariableDeclarator declarator : context.compilationUnit().findAll(VariableDeclarator.class)) {
            Optional<String> fileMethod = fileStreamCall(declarator);
            if (fileMethod.isEmpty() || TryWithResources.declares(declarator)) {
                continue;
            }
            out.report(
                    declarator,
                    "Local variable " + declarator.getNameAsString() + " holds the stream returned by "
                            + "Files." + fileMethod.get() + "(), which is never closed.",
                    "Files." + fileMethod.get() + "() returns a stream that keeps the file open "
                            + "until it is closed, and consuming it does not close it. Wrap it in "
                            + "try-with-resources, or collect it into a collection up front and drop "
                            + "the stream.");
        }
    }

    private Optional<String> fileStreamCall(VariableDeclarator declarator) {
        return declarator.getInitializer()
                .filter(Expression::isMethodCallExpr)
                .map(Expression::asMethodCallExpr)
                .filter(UnclosedFileStreamRule::isFilesQualified)
                .map(MethodCallExpr::getNameAsString)
                .filter(FILE_STREAM_METHODS::contains);
    }

    /**
     * True when the call is qualified by something that reads as {@code Files}: the name
     * {@code Files} itself, or a field access ending in it.
     */
    private static boolean isFilesQualified(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) {
            return false;
        }
        Expression target = scope.get();
        if (target instanceof NameExpr name) {
            return "Files".equals(name.getNameAsString());
        }
        if (target instanceof FieldAccessExpr fieldAccess) {
            return "Files".equals(fieldAccess.getNameAsString());
        }
        return false;
    }

    /** Exposed so the test suite can pin the documented method list. */
    public static List<String> fileStreamMethods() {
        return FILE_STREAM_METHODS.stream().sorted().toList();
    }
}
