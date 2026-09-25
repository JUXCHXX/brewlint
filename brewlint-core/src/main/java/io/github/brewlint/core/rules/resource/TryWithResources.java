package io.github.brewlint.core.rules.resource;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.Optional;

/**
 * Tells a try-with-resources declaration from an ordinary local.
 *
 * <p>Shared by the two resource rules so "is this managed by a try?" is answered once. The answer
 * has to compare by identity: JavaParser node equality is structural, so two identical resource
 * declarations in the same {@code try} would both match under {@code equals}.
 */
final class TryWithResources {

    private TryWithResources() {
    }

    static boolean declares(VariableDeclarator declarator) {
        Optional<Node> expressionNode = declarator.getParentNode();
        if (expressionNode.isEmpty() || !(expressionNode.get() instanceof VariableDeclarationExpr expression)) {
            return false;
        }
        Optional<Node> statementNode = expression.getParentNode();
        if (statementNode.isEmpty() || !(statementNode.get() instanceof TryStmt tryStatement)) {
            return false;
        }
        for (Expression resource : tryStatement.getResources()) {
            if (resource == expression) {
                return true;
            }
        }
        return false;
    }
}
