package io.github.brewlint.core.rules.resource;

import io.github.brewlint.core.model.Finding;
import io.github.brewlint.core.model.Severity;
import io.github.brewlint.core.testing.RuleTester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RES001: resource assigned to a variable nothing closes")
class UnclosedResourceRuleTest {

    private final UnclosedResourceRule rule = new UnclosedResourceRule();

    private List<Finding> check(String classBody) {
        return RuleTester.checkInClass(rule, classBody);
    }

    @Nested
    @DisplayName("reports")
    class Reports {

        @Test
        @DisplayName("local InputStream that is never closed")
        void unclosedInputStream() {
            List<Finding> findings = check("""
                    void read() throws Exception {
                        InputStream in = new FileInputStream("data.txt");
                        in.read();
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().ruleId()).isEqualTo("RES001");
            assertThat(findings.getFirst().severity()).isEqualTo(Severity.ERROR);
            assertThat(findings.getFirst().message())
                    .contains("Local variable in")
                    .contains("InputStream")
                    .contains("never closed");
            assertThat(findings.getFirst().suggestion()).contains("try-with-resources");
        }

        @Test
        @DisplayName("concrete subtype, which is the common declaration style")
        void unclosedFileInputStream() {
            List<Finding> findings = check("""
                    void read() throws Exception {
                        FileInputStream in = new FileInputStream("data.txt");
                        in.read();
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("FileInputStream");
        }

        @Test
        @DisplayName("var declaration, where the type comes from the initialiser")
        void unclosedVarDeclaration() {
            List<Finding> findings = check("""
                    void read() throws Exception {
                        var in = new FileInputStream("data.txt");
                        in.read();
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("FileInputStream");
        }

        @Test
        @DisplayName("resource in a field, which is also shared across requests")
        void unclosedField() {
            List<Finding> findings = check("""
                    private InputStream cached;
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).startsWith("Field cached");
            assertThat(findings.getFirst().suggestion()).contains("shared across requests");
        }

        @Test
        @DisplayName("JDBC Connection, a resource type outside java.io")
        void unclosedConnection() {
            List<Finding> findings = check("""
                    void run() throws Exception {
                        Connection connection = DriverManager.getConnection("jdbc:h2:mem:test");
                        connection.createStatement();
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("Connection");
        }

        @Test
        @DisplayName("java.util.zip.ZipFile")
        void unclosedZipFile() {
            List<Finding> findings = check("""
                    void run() throws Exception {
                        ZipFile zip = new ZipFile("archive.zip");
                        zip.entries();
                    }
                    """);

            assertThat(findings).hasSize(1);
            assertThat(findings.getFirst().message()).contains("ZipFile");
        }

        @Test
        @DisplayName("several leaks in one method")
        void severalLeaks() {
            List<Finding> findings = check("""
                    void run() throws Exception {
                        InputStream first = new FileInputStream("a.txt");
                        OutputStream second = new FileOutputStream("b.txt");
                        first.read();
                        second.write(1);
                    }
                    """);

            assertThat(findings).extracting(Finding::message)
                    .anyMatch(message -> message.contains("first"))
                    .anyMatch(message -> message.contains("second"));
        }
    }

    @Nested
    @DisplayName("does not report")
    class DoesNotReport {

        @Test
        @DisplayName("try-with-resources, which is the fix rather than the bug")
        void tryWithResources() {
            assertThat(check("""
                    void read() throws Exception {
                        try (InputStream in = new FileInputStream("data.txt")) {
                            in.read();
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("several resources in one try-with-resources")
        void tryWithSeveralResources() {
            assertThat(check("""
                    void copy() throws Exception {
                        try (InputStream in = new FileInputStream("a.txt");
                             OutputStream out = new FileOutputStream("b.txt")) {
                            in.transferTo(out);
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("manual try/finally, which is correct code in older style")
        void manualTryFinally() {
            assertThat(check("""
                    void read() throws Exception {
                        InputStream in = new FileInputStream("data.txt");
                        try {
                            in.read();
                        } finally {
                            in.close();
                        }
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("method parameter, because the caller owns it")
        void methodParameter() {
            assertThat(check("""
                    void read(InputStream in) throws Exception {
                        in.read();
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a type that is not a resource")
        void notAResource() {
            assertThat(check("""
                    void run() {
                        String name = "brewlint";
                        int count = 0;
                        List<String> names = new ArrayList<>();
                        names.add(name);
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("Files.lines, which RES002 owns rather than RES001")
        void filesLinesIsNotRes001sJob() {
            assertThat(check("""
                    void count() throws Exception {
                        var lines = Files.lines(Path.of("data.txt"));
                        lines.forEach(System.out::println);
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a resource returned directly, with no variable to leak")
        void returnedInline() {
            assertThat(check("""
                    InputStream open() throws Exception {
                        return new FileInputStream("data.txt");
                    }
                    """)).isEmpty();
        }
    }

    @Nested
    @DisplayName("documented limitations")
    class Limitations {

        @Test
        @DisplayName("a project type extending a JDK resource is not recognised")
        void projectSubtypeNotRecognised() {
            assertThat(check("""
                    void run() throws Exception {
                        CsvSource source = new CsvSource("data.csv");
                        source.read();
                    }
                    """)).isEmpty();
        }

        @Test
        @DisplayName("a field closed in @PreDestroy is still reported, deliberately")
        void fieldClosedInLifecycleCallback() {
            List<Finding> findings = check("""
                    private InputStream cached = new FileInputStream("a.txt");

                    @jakarta.annotation.PreDestroy
                    void shutdown() throws Exception {
                        cached.close();
                    }
                    """);

            // Reported on purpose: the rule only looks for close() in the field's own initialiser,
            // and a resource in a field is a problem worth raising even when it is closed eventually.
            assertThat(findings).hasSize(1);
        }
    }
}
