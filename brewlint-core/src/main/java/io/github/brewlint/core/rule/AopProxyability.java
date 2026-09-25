package io.github.brewlint.core.rule;

import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the rules need to know about Spring's proxy requirement, in one place.
 *
 * <p>Three rules would otherwise reimplement the same question: "can Spring actually apply this
 * annotation to this method?". Hito 1 wrote it inline in AOP001; extracting it here means Hito 2's
 * transactional rules reuse the same answer instead of drifting from it.
 */
public final class AopProxyability {

    private AopProxyability() {
    }

    /**
     * Modifiers that make a method unproxyable, in the order a developer would fix them.
     *
     * <p>Empty means CGLIB can subclass the method and the advice will run.
     */
    public static List<String> blockers(MethodDeclaration method) {
        List<String> blockers = new ArrayList<>(3);
        if (method.hasModifier(Modifier.Keyword.PRIVATE)) {
            blockers.add("private");
        }
        if (method.hasModifier(Modifier.Keyword.STATIC)) {
            blockers.add("static");
        }
        if (method.hasModifier(Modifier.Keyword.FINAL)) {
            blockers.add("final");
        }
        return blockers;
    }

    public static boolean isProxyable(MethodDeclaration method) {
        return blockers(method).isEmpty();
    }

    /** Joins the blockers into a phrase that reads naturally in a sentence: "private static". */
    public static String describe(List<String> blockers) {
        return String.join(" ", blockers);
    }

    /**
     * Every proxy-dependent annotation on the method, in source order.
     *
     * <p>Plural because a method can carry more than one, as in {@code @Async @Cacheable}. A rule
     * that reports each one separately needs all of them; {@link #hasProxyDependentAnnotation} is
     * the cheaper question.
     *
     * <p>Does not consider class-level annotations. That is deliberate: a class-level
     * {@code @Transactional} applies to its public methods only, so a caller checking "is this
     * specific method transactional" must decide for itself how to treat the class.
     */
    public static List<String> proxyDependentAnnotations(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .filter(AopAnnotations::isProxyDependent)
                .toList();
    }

    public static boolean hasProxyDependentAnnotation(MethodDeclaration method) {
        return method.getAnnotations().stream()
                .anyMatch(annotation -> AopAnnotations.isProxyDependent(annotation.getName().getIdentifier()));
    }

    /**
     * The fix for an unproxyable method, phrased for the blocker that has to change first.
     *
     * @param blocker one of {@code private}, {@code static}, {@code final}
     */
    public static String suggestionFor(String blocker) {
        return switch (blocker) {
            case "static" -> "Remove 'static' so Spring can subclass the method, or move the "
                    + "transactional work into a non-static method on another bean.";
            case "final" -> "Remove 'final' so Spring can subclass the method, or move the "
                    + "transactional work into a non-final method on another bean.";
            default -> "Make the method public and call it from another bean. If it must stay "
                    + "private, move the annotated method into its own Spring bean and delegate to it "
                    + "from the caller.";
        };
    }
}
