package io.github.brewlint.core.rules.resource;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.type.Type;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;

import java.util.List;
import java.util.Optional;

/**
 * RES001: a resource is assigned to a variable that nothing ever closes.
 *
 * <p>Covers the JDK types that hold an operating system handle: streams, readers, writers, JDBC
 * objects, zip files and channels. An {@code InputStream} keeps a file descriptor open until
 * {@code close()} is called. Under load the process runs out of descriptors and starts throwing
 * {@code Too many open files}, usually nowhere near the code that leaked.
 *
 * <h2>Deliberately not reported</h2>
 * <ul>
 *   <li>Variables declared as try-with-resources. That is the fix, not the bug.</li>
 *   <li>Method parameters. The caller owns them, so this method is not the leak. Since JavaParser
 *       models a parameter as its own node rather than a variable declarator, these are never even
 *       visited.</li>
 *   <li>Variables that are the target of a {@code close()} call. A manual {@code try/finally} is
 *       correct code, only older style.</li>
 * </ul>
 * Each exclusion removes a class of false positive that would otherwise fire on correct code.
 *
 * <h2>Known limitation</h2>
 * The declared type is read from the source, so a project type such as
 * {@code class CsvSource extends InputStream} is not recognised. See
 * {@link io.github.brewlint.core.type.TypeSolver} for why that is acceptable here and how it gets
 * fixed later.
 */
public final class UnclosedResourceRule implements Rule {

    private static final String[] RESOURCE_TYPES = {
            "java.io.InputStream",
            "java.io.OutputStream",
            "java.io.Reader",
            "java.io.Writer",
            "java.sql.Connection",
            "java.sql.Statement",
            "java.sql.ResultSet",
            "java.util.zip.ZipFile",
            "java.nio.channels.Channel"
    };

    @Override
    public String id() {
        return "RES001";
    }

    @Override
    public String category() {
        return "resource";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public void analyze(RuleContext context, RuleCollector out) {
        for (VariableDeclarator declarator : context.compilationUnit().findAll(VariableDeclarator.class)) {
            String typeName = effectiveTypeName(declarator);
            if (typeName == null || !context.typeSolver().isAnyOf(typeName, RESOURCE_TYPES)) {
                continue;
            }
            if (isTryWithResource(declarator) || isClosedManually(declarator, declarator.getNameAsString())) {
                continue;
            }

            boolean isField = isField(declarator);
            String name = declarator.getNameAsString();

            out.report(
                    declarator,
                    (isField ? "Field " : "Local variable ") + name + " holds a " + typeName
                            + " that is never closed.",
                    "Use try-with-resources: try (" + typeName + " " + name + " = ...) { ... }."
                            + (isField
                                    ? " A resource in a field is also shared across requests; move it inside the method that uses it."
                                    : ""));
        }
    }

    private boolean isField(VariableDeclarator declarator) {
        return declarator.getParentNode().filter(FieldDeclaration.class::isInstance).isPresent();
    }

    /**
     * True when the declaration is a try-with-resources entry, i.e.
     * {@code try (InputStream in = ...) }.
     */
    private boolean isTryWithResource(VariableDeclarator declarator) {
        Optional<Node> expressionNode = declarator.getParentNode();
        if (expressionNode.isEmpty() || !(expressionNode.get() instanceof VariableDeclarationExpr expression)) {
            return false;
        }
        Optional<Node> statementNode = expression.getParentNode();
        if (statementNode.isEmpty() || !(statementNode.get() instanceof TryStmt tryStatement)) {
            return false;
        }
        // Identity, not equals: JavaParser node equality is structural, so two identical resource
        // declarations in the same try would both match with equals.
        for (Expression resource : tryStatement.getResources()) {
            if (resource == expression) {
                return true;
            }
        }
        return false;
    }

    /**
     * Looks for {@code name.close()} in the enclosing method, or in the field's own initialiser for
     * fields. A manual {@code try/finally} is correct code, so reporting it would be wrong.
     */
    private boolean isClosedManually(VariableDeclarator declarator, String variableName) {
        Node scope = declarator.findAncestor(MethodDeclaration.class)
                .<Node>map(method -> method)
                .or(() -> declarator.findAncestor(FieldDeclaration.class).<Node>map(field -> field))
                .orElse(declarator);

        for (MethodCallExpr call : scope.findAll(MethodCallExpr.class)) {
            if (!"close".equals(call.getNameAsString())) {
                continue;
            }
            if (call.getScope()
                    .filter(NameExpr.class::isInstance)
                    .map(NameExpr.class::cast)
                    .map(NameExpr::getNameAsString)
                    .filter(variableName::equals)
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    /**
     * The type to test, falling back to the initialiser when the declaration uses {@code var} or
     * omits the type. Without this, {@code var in = new FileInputStream(f)} would be invisible.
     */
    private String effectiveTypeName(VariableDeclarator declarator) {
        Type declared = declarator.getType();
        if (declared == null || declared.isVarType()) {
            return typeFromInitialiser(declarator);
        }
        return declared.asString();
    }

    private String typeFromInitialiser(VariableDeclarator declarator) {
        return declarator.getInitializer()
                .filter(Expression::isObjectCreationExpr)
                .map(Expression::asObjectCreationExpr)
                .map(ObjectCreationExpr::getType)
                .map(Type::asString)
                .or(() -> declarator.getInitializer()
                        .filter(Expression::isCastExpr)
                        .map(Expression::asCastExpr)
                        .map(CastExpr::getType)
                        .map(Type::asString))
                .orElse(null);
    }

    /** Exposed so the test suite can assert the type list stays in sync with the documentation. */
    public static List<String> resourceTypes() {
        return List.of(RESOURCE_TYPES);
    }
}
