package io.github.brewlint.core.project;

import java.util.Set;

/**
 * What a first pass over the sources knows about one declared type.
 *
 * <p>Deliberately shallow: names, annotations and the declared scope. A full symbol table is what
 * {@code JavaSymbolSolver} is for, and pulling that in would cost startup time and a classpath for
 * the sake of one rule.
 *
 * @param qualifiedName fully qualified name as declared
 * @param simpleName     the name a field or parameter would be written with
 * @param annotations    simple names of every annotation on the type
 * @param scope          the declared Spring scope, from {@code @Scope("...")} or a stereotype's
 *                       {@code scope} attribute; {@code null} when the type declares none, which
 *                       means the default singleton scope
 */
public record TypeInfo(String qualifiedName, String simpleName, Set<String> annotations, String scope) {

    private static final Set<String> STEREOTYPES = Set.of(
            "Component", "Service", "Repository", "Controller", "RestController", "Configuration");

    public TypeInfo {
        annotations = Set.copyOf(annotations);
    }

    public boolean hasAnnotation(String simpleAnnotationName) {
        return annotations.contains(simpleAnnotationName);
    }

    /** True if the type is explicitly scoped as a prototype, whatever the syntax used. */
    public boolean isPrototypeScoped() {
        return scope != null && "prototype".equalsIgnoreCase(scope.trim());
    }

    /** True if the type is a Spring bean under the default, singleton scope. */
    public boolean isSingletonBean() {
        return isBeanStereotype() && !isPrototypeScoped();
    }

    /** True if the type carries a Spring stereotype annotation. */
    public boolean isBeanStereotype() {
        return annotations.stream().anyMatch(STEREOTYPES::contains);
    }

    public static boolean isBeanStereotype(String annotationSimpleName) {
        return STEREOTYPES.contains(annotationSimpleName);
    }
}
