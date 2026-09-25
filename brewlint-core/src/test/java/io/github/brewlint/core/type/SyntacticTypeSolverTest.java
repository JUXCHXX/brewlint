package io.github.brewlint.core.type;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SyntacticTypeSolver: classpath-free type resolution")
class SyntacticTypeSolverTest {

    private final SyntacticTypeSolver solver = new SyntacticTypeSolver();

    @Test
    @DisplayName("every edge in the hierarchy table is true in the real JDK")
    void hierarchyTableMatchesTheJdk() throws ClassNotFoundException {
        // This is the test that justifies the hand-written table. Two wrong edges were found this
        // way: java.sql types implement AutoCloseable rather than Closeable, and SocketChannel
        // implements ByteChannel rather than SeekableByteChannel. Both would have silently weakened
        // the rules.
        for (String typeName : SyntacticTypeSolver.knownTypes()) {
            Class<?> type = Class.forName(typeName);
            for (String declaredSuper : SyntacticTypeSolver.declaredSupertypesOf(typeName)) {
                Class<?> supertype = Class.forName(declaredSuper);
                assertThat(supertype.isAssignableFrom(type))
                        .as("%s must be assignable to %s", typeName, declaredSuper)
                        .isTrue();
            }
        }
    }

    @Nested
    @DisplayName("isA")
    class IsA {

        @ParameterizedTest
        @CsvSource({
                "InputStream,             java.io.InputStream",
                "java.io.InputStream,    java.io.InputStream",
                "FileInputStream,         java.io.InputStream",
                "java.io.FileInputStream, java.io.InputStream",
                "BufferedInputStream,     java.io.InputStream",
                "ByteArrayInputStream,    java.io.InputStream",
                "ObjectInputStream,       java.io.InputStream",
                "FileReader,              java.io.Reader",
                "BufferedReader,          java.io.Reader",
                "InputStreamReader,       java.io.Reader",
                "LineNumberReader,        java.io.Reader",
                "FileWriter,              java.io.Writer",
                "BufferedWriter,          java.io.Writer",
                "PrintWriter,             java.io.Writer",
                "FileOutputStream,        java.io.OutputStream",
                "PrintStream,             java.io.OutputStream",
                "Connection,              java.sql.Connection",
                "PreparedStatement,       java.sql.Statement",
                "ResultSet,               java.lang.AutoCloseable",
                "Connection,              java.lang.AutoCloseable",
                "ZipFile,                 java.util.zip.ZipFile",
                "Scanner,                 java.io.Closeable",
                "FileChannel,             java.nio.channels.Channel",
        })
        @DisplayName("recognises a subtype")
        void recognisesSubtypes(String declared, String target) {
            assertThat(solver.isA(declared, target)).isTrue();
        }

        @ParameterizedTest
        @CsvSource({
                "String,       java.io.InputStream",
                "Integer,      java.lang.Number",
                "FileInputStream, java.io.Reader",
                "BufferedReader, java.io.Writer",
                "PreparedStatement, java.sql.ResultSet",
                "MyCustomType, java.io.InputStream",
        })
        @DisplayName("rejects an unrelated or wrong-direction type")
        void rejectsUnrelated(String declared, String target) {
            assertThat(solver.isA(declared, target)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"java.util.List<java.lang.String>", "Map<String, List<Integer>>", "byte[]"})
        @DisplayName("normalises generics and arrays before comparing")
        void handlesGenericsAndArrays(String declared) {
            assertThat(solver.isA(declared, "java.lang.Object")).isTrue();
            assertThat(solver.isA(declared, "java.io.InputStream")).isFalse();
        }

        @Test
        @DisplayName("a resource type reaches java.lang.AutoCloseable transitively")
        void reachesAutoCloseable() {
            assertThat(solver.isA("FileInputStream", "java.lang.AutoCloseable")).isTrue();
        }

        @Test
        @DisplayName("null and blank input are answered, not thrown")
        void toleratesNullAndBlank() {
            assertThat(solver.isA(null, "java.io.InputStream")).isFalse();
            assertThat(solver.isA("InputStream", null)).isFalse();
            assertThat(solver.isA("  ", "java.io.InputStream")).isFalse();
        }

        @Test
        @DisplayName("annotations and final are stripped")
        void stripsModifiers() {
            assertThat(SyntacticTypeSolver.normalise("final java.io.InputStream")).isEqualTo("InputStream");
            assertThat(SyntacticTypeSolver.normalise("@Nullable InputStream")).isEqualTo("InputStream");
            assertThat(SyntacticTypeSolver.normalise("java.io.InputStream[]")).isEqualTo("InputStream");
        }

        @Test
        @DisplayName("isAnyOf answers the resource question in one call")
        void isAnyOf() {
            assertThat(solver.isAnyOf("FileReader", "java.io.Reader", "java.io.InputStream")).isTrue();
            assertThat(solver.isAnyOf("String", "java.io.Reader", "java.io.InputStream")).isFalse();
        }
    }
}
