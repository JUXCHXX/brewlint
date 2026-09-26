package io.github.brewlint.core.rules.performance;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.DoStmt;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.project.TypeInfo;
import io.github.brewlint.core.rule.Rule;
import io.github.brewlint.core.rule.RuleCollector;
import io.github.brewlint.core.rule.RuleContext;

import java.util.List;
import java.util.Set;

/**
 * PERF001: a database query inside a loop.
 *
 * <p>The N+1. One query to fetch a list, then one more per element of that list, so a page of twenty
 * rows costs twenty-one round trips to the database and the response time is the sum of all of them
 * rather than the slowest. It is the most common performance bug in a Spring application and the one
 * that survives review most often, because the code that causes it looks entirely reasonable: a
 * loop, and a call on the thing the loop is over.
 *
 * <h2>Why the id is not N1Q001</h2>
 * The category is {@code performance} and the id is {@code PERF001}, not something with an N in it.
 * A rule id becomes a suppression comment, a filter in an editor and a number in a dashboard, and all
 * three outlive the rule that chose its own name. Putting the diagnosis in the id cements a claim
 * about the cause into a string that then gets compared against. The cause is a hypothesis; the
 * finding is a shape.
 *
 * <h2>What this rule claims, and what it does not</h2>
 * It reports a call to a data-access method that appears textually inside the body of a loop. That
 * is a shape, not a proof, and the distinction is worth being explicit about:
 *
 * <ul>
 *   <li><strong>It is a false positive</strong> when Hibernate is configured to batch fetches, with
 *       {@code @BatchSize} on the entity or
 *       {@code hibernate.default_batch_fetch_size}. Nothing in the source under analysis says so,
 *       because that setting lives in a properties file or an annotation this rule cannot see. The
 *       finding says "likely" and the suggestion says to check the batching configuration.</li>
 *   <li><strong>It is a false positive</strong> when the loop runs a small, bounded number of times
 *       and the query is trivially indexed. Also invisible from the source.</li>
 *   <li><strong>It is a false negative</strong> when the loop is inside a method that is itself
 *       called once per element, which is the same N+1 with the loop written somewhere else.</li>
 * </ul>
 * So it is reported at WARNING rather than ERROR, and this javadoc is the honest description rather
 * than a disclaimer appended to a claim the rule cannot support.
 *
 * <h2>Why the receiver has to be a known repository</h2>
 * "A method call inside a loop" is not a finding. {@code list.add(x)}, {@code log.info(...)} and
 * {@code result.set(i, v)} are all method calls inside loops and all entirely correct. The rule
 * therefore only fires when it can identify the receiver as a Spring Data repository, resolved
 * through the project index rather than by guessing from a name, which is why it declares
 * {@link #requiresProjectIndex()}. A repository interface it cannot find means no finding, not a
 * weaker finding.
 */
public final class QueryInsideLoopRule implements Rule {

    private static final String REPOSITORY_STEREOTYPE = "Repository";

    private static final String REPOSITORY_DEFINITION = "RepositoryDefinition";

    /**
     * JPA calls that are a query by name, whatever the receiver is called.
     *
     * <p>These are on {@code EntityManager} and {@code Session}, which are resolved by type, and
     * unlike a Spring Data repository they cannot be found in the project's own sources because they
     * come from a library. Naming them is what makes this half of the rule work without symbol
     * resolution.
     */
    private static final Set<String> JPA_QUERY_METHODS = Set.of("find", "getReference", "getOne", "findAll");

    @Override
    public String id() {
        return "PERF001";
    }

    @Override
    public String category() {
        return "performance";
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
        Set<String> repositoryFields = repositoryFieldNames(context);

        for (ClassOrInterfaceDeclaration type
                : context.compilationUnit().findAll(ClassOrInterfaceDeclaration.class)) {
            for (Node loop : loopsIn(type)) {
                MethodCallExpr query = firstQueryIn(loop, repositoryFields);
                if (query != null) {
                    out.report(query, message(query), suggestion());
                }
            }
        }
    }

    /**
     * Every loop in the class, and one lambda for a {@code forEach} call.
     *
     * <p>{@code collection.forEach(item -> ...)} is a loop written as a method call, and missing it
     * would miss the form used by anyone who has read about streams.
     */
    private static List<Node> loopsIn(ClassOrInterfaceDeclaration type) {
        List<Node> loops = new java.util.ArrayList<>(type.findAll(ForStmt.class));
        loops.addAll(type.findAll(ForEachStmt.class));
        loops.addAll(type.findAll(WhileStmt.class));
        loops.addAll(type.findAll(DoStmt.class));

        for (MethodCallExpr call : type.findAll(MethodCallExpr.class)) {
            if (call.getNameAsString().equals("forEach") && call.getArguments().stream()
                    .anyMatch(argument -> argument instanceof LambdaExpr)) {
                loops.add(call);
            }
        }
        return loops;
    }

    /**
     * The first call in the loop that looks like a query, or null.
     *
     * <p>One finding per loop rather than one per call, because a loop that queries three times is
     * one problem with three symptoms, and three findings on three lines makes the report look like
     * three problems.
     */
    private MethodCallExpr firstQueryIn(Node loop, Set<String> repositoryFields) {
        for (MethodCallExpr call : loop.findAll(MethodCallExpr.class)) {
            if (isQuery(call, repositoryFields)) {
                return call;
            }
        }
        return null;
    }

    private boolean isQuery(MethodCallExpr call, Set<String> repositoryFields) {
        if (isRepositoryCall(call, repositoryFields)) {
            return true;
        }
        return isJpaCall(call);
    }

    /** True when the receiver is a field whose declared type is a Spring Data repository. */
    private boolean isRepositoryCall(MethodCallExpr call, Set<String> repositoryFields) {
        String receiver = receiverName(call);
        return receiver != null && repositoryFields.contains(receiver);
    }

    /**
     * A call to a repository's own method, and not to something it returns.
     *
     * <p>{@code orderRepository.findById(id)} is a query. {@code orderRepository.count()} is too, and
     * so is anything else on the interface, because every unrecognised method on a Spring Data
     * repository is a derived query. That is the right default here: the loop is the finding, and
     * the method name is a detail.
     */
    private boolean isJpaCall(MethodCallExpr call) {
        // getScope() is an Optional, and a call with no scope is a.find(): a local call, never a
        // query on an EntityManager, and a null here is the answer rather than a case to handle.
        return call.getScope().filter(QueryInsideLoopRule::isEntityManagerReceiver).isPresent()
                && JPA_QUERY_METHODS.contains(call.getNameAsString());
    }

    /**
     * Whether the scope is an {@code EntityManager} or a Hibernate {@code Session}.
     *
     * <p>By name, and named as a limitation: {@code entityManager}, {@code em} and {@code session}
     * are the conventions, and a field called anything else is missed. Guessing from the method name
     * alone would be worse, because {@code find} and {@code get} are common method names on classes
     * that have nothing to do with JPA, and a finding on a {@code List.find(...)} is a finding on
     * nothing.
     */
    private static boolean isEntityManagerReceiver(Expression scope) {
        String name = receiverName(scope);
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("entitymanager") || lower.equals("em") || lower.equals("session");
    }

    /** The identifier a call is made on, for {@code repo.findAll()}, {@code this.repo.findAll()}. */
    private static String receiverName(MethodCallExpr call) {
        return receiverName(call.getScope().orElse(null));
    }

    /**
     * The last segment of a receiver chain, or null when the receiver is not a named thing.
     *
     * <p>{@code this.repo.findAll()} has {@code this.repo} as its scope, which is a
     * {@code FieldAccessExpr} naming {@code repo}, so the field name is found. {@code this.findAll()}
     * has a bare {@code ThisExpr} as its scope, which names no field at all: the call is on the
     * object itself, and a field holding a repository is not involved.
     */
    private static String receiverName(Expression scope) {
        if (scope instanceof NameExpr name) {
            return name.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr access) {
            return access.getNameAsString();
        }
        return null;
    }

    /**
     * Fields in the class whose declared type is a Spring Data repository.
     *
     * <p>Resolved through the project index, and only when the type is found there. A repository
     * interface that lives in a dependency the scan never saw cannot be confirmed, so a field of
     * that type produces no finding rather than an assumed one.
     */
    private static Set<String> repositoryFieldNames(RuleContext context) {
        Set<String> names = new java.util.LinkedHashSet<>();

        for (FieldDeclaration field : context.compilationUnit().findAll(FieldDeclaration.class)) {
            for (VariableDeclarator declarator : field.getVariables()) {
                String simpleName = simpleNameOf(declarator.getType().asString());
                if (isRepository(context, simpleName)) {
                    names.add(declarator.getNameAsString());
                }
            }
        }
        return names;
    }

    /**
     * Whether a field's declared type is a repository, using the file's own imports to disambiguate.
     *
     * <p>The first version of this asked the index whether <em>every</em> type with the simple name
     * is a repository. That is the rule BEAN002 uses, and it is right there, where both meanings of
     * an ambiguous name are the same bug.
     *
     * <p>It is wrong here, and a fixture is what showed it. Two projects can each declare an
     * {@code OrderRepository}, so a plain class by that name anywhere in the codebase switched N+1
     * detection off for every other file: a finding in one file had come to depend on an unrelated
     * class in another. That is not a rule, it is a coincidence with a threshold.
     *
     * <p>The file's own imports settle it. A field declared as {@code OrderRepository} in a file
     * that imports {@code com.example.broken.OrderRepository} means that one, and no ambiguity
     * survives that. When the imports do not resolve it the rule stays quiet, and then it is quiet
     * about this file rather than about the whole project.
     */
    private static boolean isRepository(RuleContext context, String simpleName) {
        if (simpleName.isEmpty()) {
            return false;
        }
        List<TypeInfo> matching = context.projectIndex().typesNamed(simpleName);
        if (matching.isEmpty()) {
            return false;
        }
        if (matching.size() == 1) {
            return isRepository(matching.get(0));
        }

        String imported = importFor(context, simpleName);
        if (imported != null) {
            for (TypeInfo candidate : matching) {
                if (candidate.qualifiedName().equals(imported)) {
                    // An import that resolves to a type which is not a repository is a definite
                    // answer, not an ambiguity to shrug at.
                    return isRepository(candidate);
                }
            }
        }

        // A type in this file's own package needs no import, so the import search above finds
        // nothing and the obvious candidate is missed. Falling back to it costs one check and fixes
        // the most common case there is, which is a field declared next to the interface it names.
        String ownPackage = context.compilationUnit().getPackageDeclaration()
                .map(PackageDeclaration::getNameAsString)
                .orElse(null);
        if (ownPackage != null) {
            List<TypeInfo> inSamePackage = matching.stream()
                    .filter(candidate -> candidate.qualifiedName().startsWith(ownPackage + "."))
                    .toList();
            if (inSamePackage.size() == 1) {
                return isRepository(inSamePackage.get(0));
            }
        }

        // Several declarations and nothing to choose between them. Silence, because acting on a coin
        // flip is how a rule starts being ignored.
        return false;
    }

    /** The qualified name this file imports under a simple name, or null. */
    private static String importFor(RuleContext context, String simpleName) {
        for (ImportDeclaration imported : context.compilationUnit().getImports()) {
            if (!imported.isAsterisk()
                    && simpleNameOf(imported.getNameAsString()).equals(simpleName)) {
                return imported.getNameAsString();
            }
        }
        return null;
    }

    private static boolean isRepository(TypeInfo type) {
        return type.hasAnnotation(REPOSITORY_STEREOTYPE)
                || type.hasAnnotation(REPOSITORY_DEFINITION);
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

    private static String message(MethodCallExpr call) {
        return call.getNameAsString() + "() is called inside a loop, so the database is queried "
                + "once per iteration. This is the N+1 pattern: one query for the list, then one "
                + "more for each element of it.";
    }

    /**
     * The suggestion names the two fixes and, deliberately, the thing that makes this rule wrong.
     *
     * <p>A finding whose fix is always the same cannot be a false positive, and this one can be.
     * Saying "check your batching configuration" in the suggestion is what stops a user spending an
     * afternoon refactoring code that was already correct because of a properties file.
     */
    private static String suggestion() {
        return "Fetch the related data once, outside the loop: a single derived query returning the "
                + "collection, a join fetch, or an EntityGraph. If the calls are already batched by "
                + "@BatchSize or hibernate.default_batch_fetch_size, this finding is a false positive "
                + "and that setting is worth saying so about in a comment.";
    }
}
