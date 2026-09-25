package io.github.brewlint.core.rules.transactional;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.AopProxyability;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * TX002: a {@code @Transactional} method is called from inside its own class.
 *
 * <p>Spring's transaction advice lives in a proxy in front of the bean. When {@code a()} calls
 * {@code b()} on {@code this}, no proxy is involved: the call goes straight to the object, so
 * {@code b()}'s {@code @Transactional} never applies. The code reads as if it is transactional and
 * the database is never told to roll back.
 *
 * <p>It is the most common Spring transaction bug there is, and review misses it because the
 * annotation is right there on the method, looking correct.
 *
 * <h2>What is deliberately not reported</h2>
 * <ul>
 *   <li>Calls to a method that is not {@code @Transactional}. There is no advice to bypass.</li>
 *   <li>Calls to a {@code private}, {@code final} or {@code static} {@code @Transactional} method.
 *       AOP001 already reports the root cause, and a proxy could never have advised such a method,
 *       so self-invocation is not what is actually wrong. Reporting both would be two findings for
 *       one bug.</li>
 *   <li>Calls qualified with another name, such as {@code orderService.place()}. That is a different
 *       bean and its proxy does apply.</li>
 *   <li>Calls to a method declared in a superclass or another file. There is no declaration in this
 *       compilation unit to check against.</li>
 *   <li>Overload mismatches. {@code charge()} calling {@code charge(String)} is only a bug if the
 *       no-argument overload is the transactional one, so arity has to match.</li>
 * </ul>
 */
public final class SelfInvokedTransactionalMethodRule implements Rule {

    private static final String TRANSACTIONAL = "Transactional";

    @Override
    public String id() {
        return "TX002";
    }

    @Override
    public String category() {
        return "transactional";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public void analyze(RuleContext context, RuleCollector out) {
        Map<String, Set<Integer>> transactionalArities = transactionalAritiesIn(context);
        if (transactionalArities.isEmpty()) {
            return;
        }

        for (MethodCallExpr call : context.compilationUnit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!isSelfCall(call) || !matchesTransactionalOverload(transactionalArities, name, call)) {
                continue;
            }
            out.report(
                    call,
                    "Calls " + name + "() from inside the same class, so it bypasses the Spring "
                            + "proxy and runs without a transaction.",
                    "Move " + name + "() into a separate bean and inject that bean here, so the call "
                            + "goes through the proxy. Alternatively enable AspectJ weaving with "
                            + "@EnableTransactionManagement(mode = AdviceMode.ASPECTJ).");
        }
    }

    private static boolean matchesTransactionalOverload(
            Map<String, Set<Integer>> transactionalArities, String name, MethodCallExpr call) {
        Set<Integer> arities = transactionalArities.get(name);
        return arities != null && arities.contains(call.getArguments().size());
    }

    /**
     * Method name to the set of parameter counts of its transactional overloads, for the methods
     * declared in this file that Spring would actually advise.
     */
    private Map<String, Set<Integer>> transactionalAritiesIn(RuleContext context) {
        Map<String, Set<Integer>> arities = new HashMap<>();
        for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
            if (!AopProxyability.isProxyable(method) || !isTransactional(method)) {
                continue;
            }
            arities
                    .computeIfAbsent(method.getNameAsString(), key -> new java.util.LinkedHashSet<>())
                    .add(method.getParameters().size());
        }
        return arities;
    }

    private static boolean isTransactional(MethodDeclaration method) {
        if (isAnnotatedTransactional(method)) {
            return true;
        }
        // A class-level @Transactional applies to the class's public methods.
        return method.isPublic() && method.getParentNode()
                .filter(ClassOrInterfaceDeclaration.class::isInstance)
                .map(ClassOrInterfaceDeclaration.class::cast)
                .map(SelfInvokedTransactionalMethodRule::isAnnotatedTransactional)
                .orElse(false);
    }

    private static boolean isAnnotatedTransactional(com.github.javaparser.ast.nodeTypes.NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .anyMatch(annotation -> TRANSACTIONAL.equals(annotation.getName().getIdentifier()));
    }

    /**
     * True when the call has no scope, or an explicit {@code this}. Any other scope names a
     * different object, whose proxy does apply.
     */
    private static boolean isSelfCall(MethodCallExpr call) {
        Optional<Expression> scope = call.getScope();
        return scope.isEmpty() || scope.get() instanceof ThisExpr;
    }
}
