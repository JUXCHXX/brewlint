package io.github.brewlint.core.rule;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Spring annotations whose behaviour depends entirely on the container being able to wrap the
 * bean in a proxy.
 *
 * <p>These share one failure mode, which is why they share one list. When a method annotated with
 * any of them is {@code private}, {@code final} or {@code static}, CGLIB cannot subclass it, the
 * advice is never applied, and the method runs exactly as if it were unannotated: no
 * transaction, no async execution, no cache. The failure is silent, which is what makes it worth
 * its own rule.
 *
 * <p>Kept here from Hito 1, rather than inside a single rule, so that Hito 2's shared
 * {@code AopProxyability} helper is an extraction and not a redesign of three rules.
 */
public final class AopAnnotations {

    /**
     * @param simpleName short name as written in source, e.g. {@code Transactional}
     * @param purpose    what silently stops working when proxying is impossible
     */
    public record AopAnnotation(String simpleName, String purpose) {}

    public static final List<AopAnnotation> PROXY_DEPENDENT = List.of(
            new AopAnnotation("Transactional", "the transaction is never started or committed"),
            new AopAnnotation("Async", "the method runs synchronously on the caller thread"),
            new AopAnnotation("Cacheable", "the cache is never consulted and results are never stored"),
            new AopAnnotation("CachePut", "the cache is never updated"),
            new AopAnnotation("CacheEvict", "stale entries are never evicted"),
            new AopAnnotation("Scheduled", "the task is never scheduled"),
            new AopAnnotation("Retryable", "failures are never retried"),
            new AopAnnotation("Recover", "the recovery handler is never wired up"));

    private static final Set<String> PROXY_DEPENDENT_NAMES = PROXY_DEPENDENT.stream()
            .map(annotation -> annotation.simpleName().toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private AopAnnotations() {
    }

    /** True if {@code annotationSimpleName} only takes effect through a Spring AOP proxy. */
    public static boolean isProxyDependent(String annotationSimpleName) {
        return annotationSimpleName != null
                && PROXY_DEPENDENT_NAMES.contains(annotationSimpleName.toLowerCase(Locale.ROOT));
    }

    /** What the annotation was supposed to do, for use in the finding message. */
    public static String purposeOf(String annotationSimpleName) {
        return PROXY_DEPENDENT.stream()
                .filter(annotation -> annotation.simpleName().equalsIgnoreCase(annotationSimpleName))
                .map(AopAnnotation::purpose)
                .findFirst()
                .orElse("the annotated behaviour is never applied");
    }
}
