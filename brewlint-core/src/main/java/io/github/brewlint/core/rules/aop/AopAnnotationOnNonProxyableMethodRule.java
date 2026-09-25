package io.github.brewlint.core.rules.aop;

import com.github.javaparser.ast.body.MethodDeclaration;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.AopAnnotations;
import io.github.brewlint.core.rule.AopProxyability;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;

import java.util.List;

/**
 * AOP001: a proxy-dependent Spring annotation sits on a method Spring cannot proxy.
 *
 * <p>Spring implements {@code @Transactional}, {@code @Async}, {@code @Cacheable},
 * {@code @Scheduled} and friends by wrapping the bean in a CGLIB subclass and intercepting its
 * methods. CGLIB cannot override a {@code private}, {@code final} or {@code static} method. Put one
 * of those annotations on such a method and the bean is created fine, the annotation is accepted
 * fine, and the advice is never applied: the method runs unproxied. No exception, no warning, no
 * log line. The transaction never commits, the async call blocks the caller, the cache is never
 * populated.
 *
 * <p>Confidence: high. This is decided by the Java language itself, not by heuristics, so the rule
 * has no false-positive risk on the construct it matches.
 *
 * <p>The list of annotations it understands, and the "can Spring proxy this" test, both live in
 * {@link AopAnnotations} and {@link AopProxyability} so the transactional rules in Hito 2 share one
 * answer instead of three.
 */
public final class AopAnnotationOnNonProxyableMethodRule implements Rule {

    @Override
    public String id() {
        return "AOP001";
    }

    @Override
    public String category() {
        return "spring-aop";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public void analyze(RuleContext context, RuleCollector out) {
        for (MethodDeclaration method : context.compilationUnit().findAll(MethodDeclaration.class)) {
            List<String> blockers = AopProxyability.blockers(method);
            if (blockers.isEmpty()) {
                continue;
            }
            for (String annotationName : AopProxyability.proxyDependentAnnotations(method)) {
                out.report(
                        method,
                        "@" + annotationName + " on a " + AopProxyability.describe(blockers)
                                + " method: Spring's proxy cannot intercept it, so "
                                + AopAnnotations.purposeOf(annotationName) + ".",
                        AopProxyability.suggestionFor(blockers.getFirst()));
            }
        }
    }
}
