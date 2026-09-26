package io.github.brewlint.core.rules.performance;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PERF001: a database query inside a loop.
 *
 * <p>The negative tests are the point of this file. "A method call inside a loop" is not a finding,
 * and a rule of that shape produces more noise than signal on the first day anybody runs it. Every
 * case below that reports nothing is a shape that appears constantly in correct code.
 */
@DisplayName("PERF001: a database query inside a loop")
class QueryInsideLoopRuleTest {

    private static final QueryInsideLoopRule rule = new QueryInsideLoopRule();

    /**
     * A Spring Data repository, in its own file.
     *
     * <p>Its own file because the rule needs the project index, and "this field is a repository" is
     * a fact about another file. That is the same reason BEAN002 declares the requirement.
     */
    private static final String REPOSITORY = """
            @Repository
            public interface OrderRepository {
                Order findById(Long id);
                java.util.List<Order> findAll();
            }
            """;

    private static final String ENTITY = """
            public class Order {
                private Long id;
            }
            """;

    /** The same repository, in a named package, for the tests that turn on import resolution. */
    private static final String ANNOTATED_REPOSITORY = """
            package com.example.broken;
            import org.springframework.stereotype.Repository;
            @Repository
            public interface OrderRepository {
                Order findById(Long id);
            }
            """;

    /**
     * Runs the rule over a service plus the other files in its project.
     *
     * <p>RuleTester analyses the first source and builds the index from the rest, so the service has
     * to go first. It reads better with the service last, so it is moved here. Getting this order
     * wrong is invisible: every positive test fails and every negative test passes, which looks
     * exactly like a rule that never fires, and it did.
     *
     * <p>Varargs, because the negative cases need a project with no repository in it and the
     * positive ones need one with.
     */
    private static List<Finding> scan(String... projectSources) {
        String service = projectSources[projectSources.length - 1];
        String[] indexSources = new String[projectSources.length - 1];
        System.arraycopy(projectSources, 0, indexSources, 0, indexSources.length);
        return RuleTester.checkWithProject(rule, prepend(service, indexSources));
    }

    private static String[] prepend(String first, String[] rest) {
        String[] all = new String[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("a repository findById inside a for loop")
        void findByIdInForLoop() {
            List<Finding> findings = scan(REPOSITORY, ENTITY, """
                    @Service
                    public class OrderService {
                        private final OrderRepository orderRepository;
                        public OrderService(OrderRepository orderRepository) {
                            this.orderRepository = orderRepository;
                        }
                        public String describe(List<Long> ids) {
                            StringBuilder out = new StringBuilder();
                            for (Long id : ids) {
                                out.append(orderRepository.findById(id).getId());
                            }
                            return out.toString();
                        }
                    }
                    """);

            assertThat(findings).hasSize(1);
            Finding finding = findings.get(0);
            assertThat(finding.ruleId()).isEqualTo("PERF001");
            assertThat(finding.severity()).isEqualTo(Severity.WARNING);
            assertThat(finding.message()).contains("inside a loop").contains("N+1");
        }

        @Test
        @DisplayName("the same query in an enhanced for, a while and a do-while")
        void everyLoopKind() {
            assertThat(scan(REPOSITORY, ENTITY, """
                    public class A {
                        private final OrderRepository repository = null;
                        void enhanced(List<Long> ids) { for (Long id : ids) { repository.findById(id); } }
                        void whileLoop(List<Long> ids) { int i = 0; while (i < ids.size()) { repository.findAll(); i++; } }
                        void doWhile(List<Long> ids) { int i = 0; do { repository.findAll(); i++; } while (i < 1); }
                    }
                    """)).hasSize(3);
        }

        @Test
        @DisplayName("a repository call inside a stream forEach, which is a loop written as a call")
        void streamForEach() {
            // The form anyone who has read about streams writes. A rule that only understands
            // statement-position loops misses every one of them.
            List<Finding> findings = scan(REPOSITORY, ENTITY, """
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            ids.forEach(id -> repository.findById(id));
                        }
                    }
                    """);

            assertThat(findings).hasSize(1);
        }

        @Test
        @DisplayName("the query through this.repository as well as through the field name")
        void thisQualifiedRepository() {
            assertThat(scan(REPOSITORY, ENTITY, """
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { this.repository.findById(id); }
                        }
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("an EntityManager find inside a loop")
        void entityManagerFind() {
            // The repository index cannot help here: EntityManager comes from a library the scan
            // never saw, so this half of the rule is what the method names are for.
            List<Finding> findings = scan("""
                    public class A {
                        private final EntityManager entityManager = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { entityManager.find(Order.class, id); }
                        }
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.get(0).message()).contains("find()");
        }

        @Test
        @DisplayName("one finding per loop, however many queries the loop body makes")
        void oneFindingPerLoop() {
            // A loop that queries three times is one problem with three symptoms. Three findings on
            // three lines makes the report read as three problems, and the person fixing it fixes
            // one of them and thinks they are done.
            assertThat(scan(REPOSITORY, ENTITY, """
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) {
                                repository.findById(id);
                                repository.findAll();
                                repository.findById(id);
                            }
                        }
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("a query whose result is used immediately, still a query in a loop")
        void queryUsedImmediately() {
            // This one was written as a negative test and was wrong. repository.findAll().size()
            // inside a loop is one query per iteration, which is the N+1, and the .size() on the
            // end of it does not make it less of a query. Recorded here so the correct behaviour is
            // pinned rather than left to be "fixed" later by someone who assumed the nesting meant
            // something.
            assertThat(scan(REPOSITORY, ENTITY, """
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { repository.findAll().size(); }
                        }
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("the suggestion names the batching that makes this a false positive")
        void suggestionIsHonest() {
            // The rule cannot see @BatchSize or hibernate.default_batch_fetch_size, so it can be
            // wrong. A suggestion that does not mention that sends someone to refactor correct code.
            List<Finding> findings = scan(REPOSITORY, ENTITY, """
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { repository.findById(id); }
                        }
                    }
                    """);

            assertThat(findings.get(0).suggestion())
                    .contains("@BatchSize")
                    .contains("false positive");
        }
    }

    @Nested
    @DisplayName("stays quiet on")
    class StaysQuiet {

        @Test
        @DisplayName("a loop with no query in it, which is most loops")
        void loopWithoutQuery() {
            assertThat(scan("""
                    public class A {
                        String join(List<String> parts) {
                            StringBuilder out = new StringBuilder();
                            for (String part : parts) { out.append(part); }
                            return out.toString();
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a query hoisted out of the loop, which is the correct version of the bug")
        void queryOutsideTheLoop() {
            // The fix for PERF001 looks exactly like this. A rule that fires on it would fire on
            // the solution.
            assertThat(scan(REPOSITORY, ENTITY, """
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            List<Order> orders = repository.findAll();
                            for (Order order : orders) { order.getId(); }
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("calls on collections, maps and strings inside a loop")
        void nonQueryCallsInsideALoop() {
            // The shape that "a method call inside a loop" would report, and the reason this rule
            // resolves the receiver instead of counting calls.
            assertThat(scan("""
                    public class A {
                        void run(List<String> names, Map<String, String> map) {
                            for (String name : names) {
                                map.put(name, name.toUpperCase());
                                name.length();
                            }
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a field whose type cannot be confirmed to be a repository")
        void unconfirmedRepositoryType() {
            // The interface is not in the scanned sources, so the simple name is a hint and not an
            // identity. Silence is right; an assumed repository is how this rule would start
            // reporting on whatever else happens to be called somethingRepository.
            assertThat(scan("""
                    public class A {
                        private final OrderRepository orderRepository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { orderRepository.findById(id); }
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a repository still fires when an unrelated class shares its name")
        void unrelatedSameNameDoesNotSilenceTheRule() {
            // The bug this rule had, found by a fixture rather than by a test. A plain
            // "class OrderRepository" somewhere else in the project made every OrderRepository in
            // the project ambiguous, and the rule went quiet everywhere. A finding in one file had
            // come to depend on an unrelated class in another.
            //
            // What settles it is where the type lives, and the file's own import says where that is.
            assertThat(scan(ANNOTATED_REPOSITORY, """
                    package com.example.other;
                    public class OrderRepository {
                    }
                    """, """
                    package com.example;
                    import com.example.broken.OrderRepository;
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { repository.findById(id); }
                        }
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("and the import is what decides it, not just the presence of both")
        void theImportDecidesWhichOne() {
            // The converse, and the stronger half. Two types share a name; the file imports the one
            // that is not a repository; the rule stays quiet. A test that only checked the
            // repository case would pass even if the rule were reporting whenever it saw the name
            // at all, which is the behaviour that makes people turn a rule off.
            assertThat(scan(ANNOTATED_REPOSITORY, """
                    package com.example.other;
                    public class OrderRepository {
                    }
                    """, """
                    package com.example;
                    import com.example.other.OrderRepository;
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { repository.findById(id); }
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("still silent when the name is ambiguous and nothing says which is meant")
        void genuinelyAmbiguousStaysQuiet() {
            // Same-package resolution is a tie-breaker, not a wildcard. Two declarations of the same
            // name is not a codebase, and guessing is how a rule earns a reputation for being wrong.
            assertThat(scan("""
                    public class A {
                        private final OrderRepository repository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { repository.findById(id); }
                        }
                    }
                    """, """
                    public interface OrderRepository {
                        Order findById(Long id);
                    }
                    """, """
                    public interface OrderRepository {
                        Order findById(Long id);
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a name shared with a type that is not a repository")
        void ambiguousSimpleName() {
            // Two declarations answer to OrderRepository and only one is annotated. A simple name is
            // a hint, and this rule declines to act on a hint two packages disagree about.
            //
            // The first draft of this test passed a bare "package com.example.Other;" as the second
            // source, which declared nothing and so created no ambiguity at all. It passed for the
            // wrong reason, and would have passed even if the rule ignored the index entirely.
            assertThat(scan(REPOSITORY, """
                    /** A plain interface that happens to share the name. */
                    public interface OrderRepository {
                        Order findById(Long id);
                    }
                    """, """
                    public class A {
                        private final OrderRepository orderRepository = null;
                        void run(List<Long> ids) {
                            for (Long id : ids) { orderRepository.findById(id); }
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("find and get on something that is not an EntityManager")
        void findOnSomethingElse() {
            // find and get are ordinary method names. A rule that matched on the name alone would
            // fire on a List.find, which is a finding about nothing.
            assertThat(scan("""
                    public class A {
                        void run(List<String> items) {
                            for (String item : items) {
                                items.find("x");
                                items.get(0);
                            }
                        }
                    }
                    """)).isEmpty();
        }
    }

    @Nested
    @DisplayName("the rule itself")
    class Metadata {

        @Test
        @DisplayName("is PERF001 in the performance category")
        void id() {
            assertThat(rule.id()).isEqualTo("PERF001");
            assertThat(rule.category()).isEqualTo("performance");
        }

        @Test
        @DisplayName("is a warning, because it can be a false positive")
        void severity() {
            // ERROR would be a claim. The rule sees a shape, and the shape can be correct because of
            // a setting in a file it cannot read.
            assertThat(rule.defaultSeverity()).isEqualTo(Severity.WARNING);
        }

        @Test
        @DisplayName("requires the project index, and would report nothing without it")
        void requiresProjectIndex() {
            assertThat(rule.requiresProjectIndex()).isTrue();
        }
    }
}
