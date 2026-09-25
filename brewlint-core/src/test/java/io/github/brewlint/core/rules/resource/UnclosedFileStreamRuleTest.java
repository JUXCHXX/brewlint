package io.github.brewlint.core.rules.resource;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RES002: stream from java.nio.file.Files never closed")
class UnclosedFileStreamRuleTest {

    private final UnclosedFileStreamRule rule = new UnclosedFileStreamRule();

    private List<Finding> check(String classBody) {
        return RuleTester.checkInClass(rule, classBody);
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("Files.lines assigned to a local")
        void filesLines() {
            List<Finding> findings = check("""
                    void count() throws Exception {
                        var lines = Files.lines(Path.of("data.csv"));
                        lines.forEach(System.out::println);
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().ruleId()).isEqualTo("RES002");
            assertThat(findings.getFirst().severity()).isEqualTo(Severity.WARNING);
            assertThat(findings.getFirst().message())
                    .contains("Files.lines()")
                    .contains("never closed");
            assertThat(findings.getFirst().suggestion()).contains("try-with-resources");
        }

        @Test
        @DisplayName("Files.walk")
        void filesWalk() {
            assertThat(check("""
                    void walk() throws Exception {
                        var paths = Files.walk(root);
                        paths.forEach(p -> {});
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("Files.list and Files.find, which leak the same way")
        void filesListAndFind() {
            assertThat(UnclosedFileStreamRule.fileStreamMethods())
                    .containsExactly("find", "lines", "list", "walk");

            assertThat(check("""
                    void listing() throws Exception {
                        var entries = Files.list(dir);
                        entries.forEach(p -> {});
                    }
                    """)).hasSize(1);
            assertThat(check("""
                    void searching() throws Exception {
                        var matches = Files.find(dir, 3, p -> true);
                        matches.forEach(p -> {});
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("an explicitly typed local")
        void explicitType() {
            assertThat(check("""
                    void count() throws Exception {
                        Stream<String> lines = Files.lines(Path.of("data.csv"));
                        lines.forEach(System.out::println);
                    }
                    """)).hasSize(1);
        }

        @Test
        @DisplayName("a fully qualified call")
        void fullyQualifiedCall() {
            assertThat(check("""
                    void count() throws Exception {
                        var lines = java.nio.file.Files.lines(Path.of("data.csv"));
                        lines.forEach(System.out::println);
                    }
                    """)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("does not report")
    class DoesNotReport {

        @Test
        @DisplayName("try-with-resources, which is the documented fix")
        void tryWithResources() {
            assertThat(check("""
                    void count() throws Exception {
                        try (var lines = Files.lines(Path.of("data.csv"))) {
                            lines.forEach(System.out::println);
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("an in-memory stream, which holds no handle")
        void collectionStream() {
            assertThat(check("""
                    void count() {
                        var stream = List.of("a", "b").stream();
                        stream.forEach(System.out::println);
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a call on some other receiver that happens to be named like one of these")
        void otherReceiver() {
            assertThat(check("""
                    void count() {
                        var result = cache.lines("key");
                        result.forEach(System.out::println);
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("an unqualified call, which without a symbol table could be anything")
        void unqualifiedCall() {
            assertThat(check("""
                    void count() throws Exception {
                        var lines = lines("data.csv");
                        lines.forEach(System.out::println);
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a Files method that does not return a stream handle")
        void otherFilesMethod() {
            assertThat(check("""
                    void check() throws Exception {
                        boolean exists = Files.exists(Path.of("data.csv"));
                        System.out.println(exists);
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a method named like a Files one on a Files field")
        void fieldReceiver() {
            assertThat(check("""
                    private final NioHelper helper = new NioHelper();
                    void count() {
                        var lines = helper.lines("data.csv");
                        lines.forEach(System.out::println);
                    }
                    """)).isEmpty();
        }
    }
}
