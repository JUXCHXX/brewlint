package io.github.brewlint.core.rules.bean;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.project.TypeInfo;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * BEAN001: a persistence entity is also annotated as a Spring bean.
 *
 * <p>With JPA, the {@code @Entity} class is instantiated by the persistence provider and managed by
 * it. Adding {@code @Component} to the same class makes component scanning pick it up and create a
 * second, detached instance, held in the application context forever. The two instances are
 * different objects with different state, which is a class of bug that is extremely hard to trace
 * back to its cause. Depending on the setup it can also fail at startup with a naming or proxying
 * conflict.
 *
 * <p>Constructor or field injection into an entity hits the same wall: JPA requires a no-argument
 * constructor and does not run field injection on the instances it creates.
 *
 * <p>Reported for Mongo's {@code @Document} too, since the failure mode is identical.
 */
public final class EntityAnnotatedAsBeanRule implements Rule {

    private static final Set<String> PERSISTENCE_ANNOTATIONS = Set.of("Entity", "Document");

    @Override
    public String id() {
        return "BEAN001";
    }

    @Override
    public String category() {
        return "bean";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.ERROR;
    }

    @Override
    public void analyze(RuleContext context, RuleCollector out) {
        for (ClassOrInterfaceDeclaration type
                : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (type.isInterface()) {
                continue;
            }
            Optional<String> persistence = firstAnnotation(type, PERSISTENCE_ANNOTATIONS);
            if (persistence.isEmpty()) {
                continue;
            }
            Optional<String> stereotype = type.getAnnotations().stream()
                    .map(annotation -> annotation.getName().getIdentifier())
                    .filter(TypeInfo::isBeanStereotype)
                    .findFirst();
            if (stereotype.isEmpty()) {
                continue;
            }

            out.report(
                    type,
                    "@" + persistence.get() + " class is also annotated @" + stereotype.get()
                            + ", so component scanning creates a second, unmanaged instance that the "
                            + "persistence provider knows nothing about.",
                    "Remove @" + stereotype.get() + ". The persistence provider already manages this "
                            + "type; if the class needs to be a Spring bean, put that behaviour in a "
                            + "separate component that operates on entities rather than being one.");
        }
    }

    private static Optional<String> firstAnnotation(
            NodeWithAnnotations<?> node, Set<String> wanted) {
        return node.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .filter(wanted::contains)
                .findFirst();
    }

    /** Exposed so the test suite can pin the annotation list against the documentation. */
    public static List<String> persistenceAnnotations() {
        return PERSISTENCE_ANNOTATIONS.stream().sorted().toList();
    }
}
