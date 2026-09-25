package io.github.brewlint.core.rules.bean;

import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;

import java.util.Optional;
import java.util.Set;

/**
 * BEAN003: a bean is wired by injecting into a field instead of through its constructor.
 *
 * <p>Field injection hides a bean's dependencies: the class looks self-contained, and nothing at the
 * call site says what it needs. It also produces a half-constructed object. Between the constructor
 * running and Spring injecting the fields, the bean exists with null dependencies, and anything that
 * touches it in that window, directly or through an event it publishes, gets a
 * {@code NullPointerException}. It is also the only form that cannot be used outside Spring, which
 * makes the class untestable without a container.
 *
 * <p><strong>This is a convention, not a defect, so the default severity is INFO.</strong> Nothing
 * breaks today. It is reported because the constructor form is strictly better and the fix is
 * mechanical, not because the code is wrong. Downgrade or disable it in {@code brewlint.yml} if it
 * does not earn its place in your report.
 */
public final class FieldInjectionRule implements Rule {

    private static final Set<String> INJECTION_ANNOTATIONS =
            Set.of("Autowired", "Inject", "Resource");

    @Override
    public String id() {
        return "BEAN003";
    }

    @Override
    public String category() {
        return "bean";
    }

    @Override
    public Severity defaultSeverity() {
        return Severity.INFO;
    }

    @Override
    public void analyze(RuleContext context, RuleCollector out) {
        for (FieldDeclaration field : context.compilationUnit().findAll(FieldDeclaration.class)) {
            Optional<String> injection = injectionAnnotationOn(field);
            if (injection.isEmpty()) {
                continue;
            }
            String fieldName = field.getVariables().stream()
                    .map(VariableDeclarator::getNameAsString)
                    .findFirst()
                    .orElse("<field>");

            out.report(
                    field,
                    "Field " + fieldName + " is injected with @" + injection.get()
                            + ", so the bean exists with this dependency null between construction "
                            + "and injection.",
                    "Make the field final and inject it through the constructor. Lombok's "
                            + "@RequiredArgsConstructor does this in one line.");
        }
    }

    private Optional<String> injectionAnnotationOn(FieldDeclaration field) {
        for (AnnotationExpr annotation : field.getAnnotations()) {
            String name = annotation.getName().getIdentifier();
            if (INJECTION_ANNOTATIONS.contains(name)) {
                return Optional.of(name);
            }
        }
        return Optional.empty();
    }

    /** The annotations that mark a field as a Spring injection point. */
    public static Set<String> injectionAnnotations() {
        return INJECTION_ANNOTATIONS;
    }
}
