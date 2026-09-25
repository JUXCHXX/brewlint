package io.github.brewlint.core.rules.transactional;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.type.ReferenceType;
import com.github.javaparser.ast.type.Type;
import com.github.javaparser.ast.type.UnionType;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.AopProxyability;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;
import io.github.brewlint.core.type.CheckedExceptions;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * TX003: a {@code @Transactional} method can throw a checked exception without a
 * {@code rollbackFor}.
 *
 * <p>Spring rolls back when a transaction is marked rollback-only, and by default it marks it for
 * {@code RuntimeException} and {@code Error}. A <em>checked</em> exception is different: unless the
 * annotation says {@code @Transactional(rollbackFor = ...)}, the transaction commits. Code that
 * throws {@code SQLException}, does its rollback dance, and rethrows therefore persists half the
 * work, with no error anywhere.
 *
 * <h2>Where this rule is deliberately conservative</h2>
 * It only fires on a checked exception it can name with certainty, from
 * {@link CheckedExceptions#isKnownChecked}. A project exception that happens to be unchecked is not
 * on that list, so the rule stays quiet rather than demanding a {@code rollbackFor} that changes
 * nothing. Missing a finding here is the cheaper mistake.
 *
 * <p>It also cannot see a checked exception thrown by a callee, because that needs a call graph.
 * {@code throws IOException} and a {@code catch (IOException)} are the two signals it can see.
 */
public final class MissingRollbackForRule implements Rule {

    private static final String TRANSACTIONAL = "Transactional";

    @Override
    public String id() {
        return "TX003";
    }

    @Override
    public String category() {
        return "transactional";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public void analyze(RuleContext context, RuleCollector out) {
        for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
            if (!AopProxyability.isProxyable(method) || !carriesTransactional(method)) {
                continue;
            }
            if (declaresRollbackFor(method) || method.getParentNode()
                    .filter(ClassOrInterfaceDeclaration.class::isInstance)
                    .map(ClassOrInterfaceDeclaration.class::cast)
                    .map(MissingRollbackForRule::declaresRollbackFor)
                    .orElse(false)) {
                continue;
            }

            List<String> checkedExceptions = exposedCheckedExceptions(method);
            if (checkedExceptions.isEmpty()) {
                continue;
            }

            out.report(
                    method,
                    "@Transactional without rollbackFor, but the method can throw the checked "
                            + "exception " + String.join(", ", checkedExceptions)
                            + ", which Spring commits rather than rolls back.",
                    "Add @Transactional(rollbackFor = "
                            + checkedExceptions.stream().map(MissingRollbackForRule::simpleName)
                                    .reduce((left, right) -> left + ".class, " + right + ".class")
                                    .orElseThrow() + ".class), so the transaction rolls back when "
                            + "the operation fails.");
        }
    }

    private static String simpleName(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot >= 0 ? qualifiedName.substring(lastDot + 1) : qualifiedName;
    }

    /**
     * Checked exceptions the method can let escape: those it declares in {@code throws}, plus those
     * it catches. Order and duplicates are normalised so the message reads once per type.
     */
    private List<String> exposedCheckedExceptions(MethodDeclaration method) {
        Set<String> found = new LinkedHashSet<>();

        for (ReferenceType declared : method.getThrownExceptions()) {
            if (CheckedExceptions.isKnownChecked(declared.asString())) {
                found.add(declared.asString());
            }
        }
        for (CatchClause catchClause : method.findAll(CatchClause.class)) {
            for (String name : typeNames(catchClause.getParameter().getType())) {
                if (CheckedExceptions.isKnownChecked(name)) {
                    found.add(name);
                }
            }
        }
        return new ArrayList<>(found);
    }

    /**
     * The type names a {@code catch} parameter can declare. A multi-catch is a
     * {@code UnionType} in the AST, so {@code catch (IOException | SQLException e)} has to yield
     * both.
     */
    private List<String> typeNames(Type type) {
        if (type instanceof UnionType union) {
            return union.getElements().stream().map(Type::asString).toList();
        }
        return List.of(type.asString());
    }

    private static boolean carriesTransactional(MethodDeclaration method) {
        if (declaresTransactional(method)) {
            return true;
        }
        // A class-level @Transactional applies to the class's public methods.
        return method.isPublic() && method.getParentNode()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .map(MissingRollbackForRule::declaresTransactional)
                .orElse(false);
    }

    private static boolean declaresTransactional(
            com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .anyMatch(annotation -> TRANSACTIONAL.equals(annotation.getName().getIdentifier()));
    }

    /**
     * True if the {@code @Transactional} annotation already names what to roll back on.
     *
     * <p>Reads the annotation's members rather than its text, so a class that happens to be called
     * {@code RollbackForHelper} cannot make this return true.
     */
    private static boolean declaresRollbackFor(
            com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .filter(annotation -> TRANSACTIONAL.equals(annotation.getName().getIdentifier()))
                .anyMatch(annotation -> {
                    if (annotation instanceof NormalAnnotationExpr normal) {
                        return normal.getPairs().stream().anyMatch(pair -> {
                            String name = pair.getNameAsString();
                            return "rollbackFor".equals(name) || "rollbackForClass".equals(name);
                        });
                    }
                    if (annotation instanceof SingleMemberAnnotationExpr single) {
                        // The single member of @Transactional is the bean name, never rollbackFor.
                        return false;
                    }
                    return false;
                });
    }
}
