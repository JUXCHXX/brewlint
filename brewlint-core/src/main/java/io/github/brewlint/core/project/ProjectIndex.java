package io.github.brewlint.core.project;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What a first pass over the sources knows about every declared type in the project.
 *
 * <p>Exists because some rules cannot work one file at a time. "A prototype-scoped bean is
 * injected into a singleton" is a statement about two different files, and a rule that only sees
 * one {@code CompilationUnit} cannot make it. Rather than special-case that rule, the engine
 * collects this small index once and hands it to every rule.
 *
 * <p><strong>It costs a second parse.</strong> Building the index means parsing every file, and
 * running the rules means parsing them again. The index itself is tiny, so nothing is cached, but
 * parse time is roughly doubled on a run that needs it. That is why it is opt-in: a rule declares
 * {@link io.github.brewlint.core.rule.Rule#requiresProjectIndex()}, and if no enabled rule does,
 * the second pass never happens. See {@link ProjectIndexBuilder}.
 *
 * <p><strong>Names are not unique.</strong> Two {@code Order} classes in different packages both
 * appear under the simple name. Rules must handle that: a simple name is only a hint, never proof.
 */
public final class ProjectIndex {

    private final Map<String, List<TypeInfo>> bySimpleName;
    private final Map<String, TypeInfo> byQualifiedName;

    /**
     * Handed to rules that did not ask for the index.
     *
     * <p>Public because the engine is in another package. A rule that declares
     * {@code requiresProjectIndex()} and then reads this has a bug in its own declaration, and
     * seeing zero types is how that becomes visible instead of a silently missed finding.
     */
    public static final ProjectIndex EMPTY = new ProjectIndex(Map.of(), Map.of());

    ProjectIndex(Map<String, List<TypeInfo>> bySimpleName, Map<String, TypeInfo> byQualifiedName) {
        this.bySimpleName = Map.copyOf(bySimpleName);
        this.byQualifiedName = Map.copyOf(byQualifiedName);
    }

    /** Every declared type with this simple name, across all packages. */
    public List<TypeInfo> typesNamed(String simpleName) {
        return bySimpleName.getOrDefault(simpleName, List.of());
    }

    /** The type with this fully qualified name, if it is declared in the scanned sources. */
    public Optional<TypeInfo> typeAt(String qualifiedName) {
        return Optional.ofNullable(byQualifiedName.get(qualifiedName));
    }

    /**
     * Every prototype-scoped type with this simple name.
     *
     * <p>Used by the prototype-into-singleton rule. When several packages declare the same simple
     * name, only the ones actually scoped as prototype are returned, and the rule requires all of
     * them to match before reporting.
     */
    public List<TypeInfo> prototypeTypesNamed(String simpleName) {
        return typesNamed(simpleName).stream().filter(TypeInfo::isPrototypeScoped).toList();
    }

    public int typeCount() {
        return byQualifiedName.size();
    }

    public Set<String> simpleNames() {
        return bySimpleName.keySet();
    }
}
