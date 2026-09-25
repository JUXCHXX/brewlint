package io.github.brewlint.core.rules.bean;

import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.project.ProjectIndex;
import io.github.brewlint.core.project.TypeInfo;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;

import java.util.List;

/**
 * BEAN002: a prototype-scoped bean is injected into a singleton.
 *
 * <p>Prototype scope exists to hand out a fresh instance on every use. That works when the
 * consumer looks the bean up each time, from the application context. It does not work when the
 * consumer is a singleton: a singleton is created once, so its injected dependency is resolved
 * once, and every user of that singleton shares the same object. The prototype scope appears to be
 * configured and is not.
 *
 * <p>This is the one rule in the set that cannot be written against a single file. "The type of
 * this field is prototype scoped" is a fact about another file, which is why the rule declares
 * {@link #requiresProjectIndex()} and the engine builds a project-wide index for it.
 *
 * <h2>How wrong a guess can be, and what this rule does about it</h2>
 * Without symbol resolution, the field's type is only a simple name, and two packages can both
 * declare an {@code Order}. So this rule only reports when it is certain:
 * <ul>
 *   <li>Every type with that simple name in the scanned sources is prototype scoped. One
 *       {@code com.a.Order} being a prototype must not implicate an unrelated
 *       {@code com.b.Order}.</li>
 *   <li>The consuming class is a Spring bean under the default singleton scope.</li>
 *   <li>The field is injected, not merely declared: an unused prototype field is not this bug.</li>
 * </ul>
 */
public final class PrototypeIntoSingletonRule implements Rule {

    @Override
    public String id() {
        return "BEAN002";
    }

    @Override
    public String category() {
        return "bean";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.WARNING;
    }

    @Override
    public boolean requiresProjectIndex() {
        return true;
    }

    @Override
    public void analyze(RuleContext context, RuleCollector out) {
        for (ClassOrInterfaceDeclaration type
                : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
            if (!isSingletonBean(type)) {
                continue;
            }
            reportInjectedFields(context, type, out);
            reportInjectedParameters(context, type, out);
        }
    }

    private void reportInjectedFields(
            RuleContext context, ClassOrInterfaceDeclaration consumer, RuleCollector out) {
        for (FieldDeclaration field : consumer.getFields()) {
            if (!isInjectionPoint(field)) {
                continue;
            }
            for (VariableDeclarator declarator : field.getVariables()) {
                String simpleName = simpleNameOf(declarator.getType().asString());
                if (isPrototypeEverywhere(context.projectIndex(), simpleName)) {
                    out.report(
                            declarator,
                            "Injects " + simpleName + ", a prototype-scoped bean, into "
                                    + consumer.getNameAsString() + ", which is a singleton, so every "
                                    + "caller shares the one instance created at startup.",
                            "Look the prototype bean up where it is needed, with "
                                    + "ObjectProvider<" + simpleName + "> or "
                                    + "ApplicationContext.getBean(" + simpleName + ".class). "
                                    + "Alternatively scope " + consumer.getNameAsString()
                                    + " as prototype too, if sharing was not the intent.");
                }
            }
        }
    }

    private void reportInjectedParameters(
            RuleContext context, ClassOrInterfaceDeclaration consumer, RuleCollector out) {
        for (var constructor : consumer.getConstructors()) {
            if (!isConstructorInjectionPoint(consumer, constructor)) {
                continue;
            }
            for (Parameter parameter : constructor.getParameters()) {
                String simpleName = simpleNameOf(parameter.getType().asString());
                if (isPrototypeEverywhere(context.projectIndex(), simpleName)) {
                    out.report(
                            parameter,
                            "Injects " + simpleName + ", a prototype-scoped bean, into the "
                                    + "singleton " + consumer.getNameAsString() + ", so every caller "
                                    + "shares the one instance created at startup.",
                            "Look the prototype bean up where it is needed, with "
                                    + "ObjectProvider<" + simpleName + ">, rather than holding it "
                                    + "as a long-lived field.");
                }
            }
        }
    }

    /**
     * Whether this constructor is where Spring performs the injection.
     *
     * <p>Two ways, and the second is the more common one: an explicit injection annotation, or a
     * lone constructor, which Spring uses implicitly with nothing written on it.
     *
     * <p><strong>JavaParser quirk.</strong> An annotation written on a constructor parameter is
     * attached to the {@code ConstructorDeclaration} node rather than the {@code Parameter}, so
     * {@code parameter.getAnnotations()} is empty for that case and the annotation has to be read
     * from the constructor. Method parameters are not affected. This is a parser behaviour, not a
     * Spring one, and it is the reason this method looks at the constructor at all.
     */
    private boolean isConstructorInjectionPoint(
            ClassOrInterfaceDeclaration consumer, ConstructorDeclaration constructor) {
        return hasInjectionAnnotation(constructor) || consumer.getConstructors().size() == 1;
    }

    /**
     * True only when the name is known and every type that answers to it is prototype scoped. A
     * single non-prototype match makes the rule stay quiet, because a simple name is a hint and not
     * an identity.
     */
    private boolean isPrototypeEverywhere(ProjectIndex index, String simpleName) {
        List<TypeInfo> matching = index.typesNamed(simpleName);
        return !matching.isEmpty() && matching.stream().allMatch(TypeInfo::isPrototypeScoped);
    }

    private boolean isSingletonBean(ClassOrInterfaceDeclaration type) {
        return type.getAnnotations().stream()
                .anyMatch(annotation -> TypeInfo.isBeanStereotype(annotation.getName().getIdentifier()));
    }

    private boolean isInjectionPoint(FieldDeclaration field) {
        return hasInjectionAnnotation(field);
    }

    private static boolean hasInjectionAnnotation(NodeWithAnnotations<?> node) {
        return node.getAnnotations().stream()
                .map(annotation -> annotation.getName().getIdentifier())
                .anyMatch(FieldInjectionRule.injectionAnnotations()::contains);
    }

    /** Strips generics and any package qualifier, leaving the name a field would be written with. */
    private static String simpleNameOf(String declaredType) {
        String name = declaredType.trim();
        int genericStart = name.indexOf('<');
        if (genericStart >= 0) {
            name = name.substring(0, genericStart);
        }
        int lastDot = name.lastIndexOf('.');
        return (lastDot >= 0 ? name.substring(lastDot + 1) : name).trim();
    }
}
