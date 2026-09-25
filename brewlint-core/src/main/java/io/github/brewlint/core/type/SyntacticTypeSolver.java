package io.github.brewlint.core.type;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Type resolution without a classpath.
 *
 * <p>Reads the type name exactly as it appears in the source and resolves it against a table of
 * well-known JDK hierarchies. No classpath, no {@code JavaSymbolSolver}, sub-millisecond. That is
 * what lets a linter start and scan in the time it takes a shell to open.
 *
 * <h2>Known limitation, by design</h2>
 * A project that declares {@code class MyStream extends InputStream} will not be recognised as an
 * {@code InputStream}, because the table only knows JDK types. This is the accepted trade-off for
 * Hito 1 and the reason {@link TypeSolver} is an interface: the table below is replaced wholesale
 * by a symbol-solver backed implementation later, and no rule changes.
 *
 * <p>Because the table is the whole truth, it is easy to get wrong. Every edge in it was read off
 * the JDK with reflection and is asserted against the JDK by
 * {@code SyntacticTypeSolverTest.hierarchyTableMatchesTheJdk}, so a bad edge fails the build rather
 * than quietly weakening a rule.
 */
public final class SyntacticTypeSolver implements TypeSolver {

    /**
     * Direct inheritance edges, one per line, formatted {@code parent <- child}
     * (read as "child is-a parent"). Deliberately not a complete model of the JDK: only the
     * hierarchies that the resource and Spring rules actually ask about.
     */
    private static final String HIERARCHY = """
            java.io.Closeable                   <- java.io.InputStream
            java.io.Closeable                   <- java.io.OutputStream
            java.io.Closeable                   <- java.io.Reader
            java.io.Closeable                   <- java.io.Writer
            java.io.Closeable                   <- java.io.FilterInputStream
            java.io.Closeable                   <- java.io.FilterOutputStream
            java.io.Closeable                   <- java.io.FilterReader
            java.io.Closeable                   <- java.io.FilterWriter
            java.lang.AutoCloseable             <- java.io.Closeable

            java.io.InputStream                 <- java.io.FileInputStream
            java.io.InputStream                 <- java.io.ByteArrayInputStream
            java.io.InputStream                 <- java.io.ObjectInputStream
            java.io.InputStream                 <- java.io.SequenceInputStream
            java.io.InputStream                 <- java.io.FilterInputStream
            java.io.FilterInputStream           <- java.io.BufferedInputStream
            java.io.FilterInputStream           <- java.io.DataInputStream
            java.io.FilterInputStream           <- java.io.PushbackInputStream

            java.io.OutputStream                <- java.io.FileOutputStream
            java.io.OutputStream                <- java.io.ByteArrayOutputStream
            java.io.OutputStream                <- java.io.ObjectOutputStream
            java.io.OutputStream                <- java.io.FilterOutputStream
            java.io.FilterOutputStream          <- java.io.BufferedOutputStream
            java.io.FilterOutputStream          <- java.io.DataOutputStream
            java.io.FilterOutputStream          <- java.io.PrintStream

            java.io.Reader                      <- java.io.InputStreamReader
            java.io.Reader                      <- java.io.StringReader
            java.io.Reader                      <- java.io.CharArrayReader
            java.io.Reader                      <- java.io.BufferedReader
            java.io.Reader                      <- java.io.FilterReader
            java.io.InputStreamReader           <- java.io.FileReader
            java.io.BufferedReader              <- java.io.LineNumberReader
            java.io.FilterReader                <- java.io.PushbackReader

            java.io.Writer                      <- java.io.OutputStreamWriter
            java.io.Writer                      <- java.io.StringWriter
            java.io.Writer                      <- java.io.CharArrayWriter
            java.io.Writer                      <- java.io.BufferedWriter
            java.io.Writer                      <- java.io.PrintWriter
            java.io.Writer                      <- java.io.FilterWriter
            java.io.OutputStreamWriter          <- java.io.FileWriter

            java.io.Closeable                   <- java.util.zip.ZipFile
            java.util.zip.ZipFile               <- java.util.jar.JarFile
            java.io.Closeable                   <- java.util.Scanner
            java.io.Closeable                   <- java.util.Formatter
            java.io.Closeable                   <- java.nio.channels.Channel

            java.lang.AutoCloseable             <- java.sql.Connection
            java.lang.AutoCloseable             <- java.sql.Statement
            java.lang.AutoCloseable             <- java.sql.ResultSet
            java.sql.Wrapper                    <- java.sql.Connection
            java.sql.Wrapper                    <- java.sql.Statement
            java.sql.Wrapper                    <- java.sql.ResultSet
            java.sql.Statement                  <- java.sql.PreparedStatement
            java.sql.Statement                  <- java.sql.CallableStatement

            java.nio.channels.Channel           <- java.nio.channels.SeekableByteChannel
            java.nio.channels.SeekableByteChannel <- java.nio.channels.FileChannel
            java.nio.channels.ByteChannel       <- java.nio.channels.SocketChannel
            """;

    private static final Set<String> PRIMITIVES = Set.of(
            "boolean", "byte", "char", "short", "int", "long", "float", "double", "void", "var");

    private static final Map<String, Set<String>> DIRECT_SUPERTYPES = parseHierarchy();
    private static final Map<String, Set<String>> SIMPLE_NAME_INDEX = indexBySimpleName();

    @Override
    public boolean isA(String declaredTypeName, String targetTypeName) {
        if (declaredTypeName == null || targetTypeName == null) {
            return false;
        }
        // The array test reads the raw text because normalise() strips the brackets, and "byte[]"
        // must not be mistaken for the primitive "byte".
        String raw = declaredTypeName.trim();
        String declared = normalise(raw);
        String target = normalise(targetTypeName);
        if (declared.isEmpty() || target.isEmpty()) {
            return false;
        }

        boolean targetIsObject = "java.lang.Object".equals(target) || "Object".equals(target);

        // Arrays are only ever assignable to Object here: a resource array is not a leak, and
        // nothing in the rule set depends on array covariance.
        if (raw.endsWith("[]")) {
            return targetIsObject;
        }
        if (PRIMITIVES.contains(declared)) {
            return false;
        }
        // Every reference type is assignable to Object, including the project types this solver
        // knows nothing about.
        if (targetIsObject) {
            return true;
        }

        for (String declaredFqn : resolveCandidates(declared)) {
            for (String targetFqn : resolveCandidates(target)) {
                if (declaredFqn.equals(targetFqn) || supertypesOf(declaredFqn).contains(targetFqn)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Returns every type reachable from {@code fqn}, including itself. */
    private Set<String> supertypesOf(String fqn) {
        if (!DIRECT_SUPERTYPES.containsKey(fqn)) {
            return Set.of(fqn);
        }
        Set<String> seen = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(fqn);
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (!seen.add(current)) {
                continue;
            }
            pending.addAll(DIRECT_SUPERTYPES.getOrDefault(current, Set.of()));
        }
        return seen;
    }

    /**
     * Turns a name as written in source into the fully qualified names it could refer to.
     *
     * <p>A qualified name maps to itself. A simple name maps to every known JDK type with that
     * simple name, plus the bare simple name, so that unknown project types still compare by name.
     */
    private List<String> resolveCandidates(String normalised) {
        if (normalised.contains(".")) {
            return List.of(normalised);
        }
        List<String> candidates = new ArrayList<>(SIMPLE_NAME_INDEX.getOrDefault(normalised, Set.of()));
        if (!candidates.contains(normalised)) {
            candidates.add(normalised);
        }
        return candidates;
    }

    /**
     * Strips everything that is not the bare type: annotations, generics, array brackets, wildcards
     * and {@code final}. {@code java.util.List<java.lang.String>} becomes {@code List}.
     */
    static String normalise(String rawTypeName) {
        String name = rawTypeName.trim();
        int genericStart = name.indexOf('<');
        if (genericStart >= 0) {
            name = name.substring(0, genericStart);
        }
        name = name.replace("...", "").replace("[]", "").replace("?", "").trim();
        for (String prefix : List.of("final ", "extends ", "super ")) {
            while (name.startsWith(prefix)) {
                name = name.substring(prefix.length()).trim();
            }
        }
        while (name.startsWith("@")) {
            int space = name.indexOf(' ');
            if (space < 0) {
                return "";
            }
            name = name.substring(space + 1).trim();
        }
        int lastDot = name.lastIndexOf('.');
        return lastDot >= 0 ? name.substring(lastDot + 1) : name;
    }

    private static Map<String, Set<String>> parseHierarchy() {
        Map<String, Set<String>> graph = new HashMap<>();
        for (String line : HIERARCHY.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int arrow = trimmed.indexOf("<-");
            if (arrow < 0) {
                continue;
            }
            String parent = trimmed.substring(0, arrow).trim();
            String child = trimmed.substring(arrow + 2).trim();
            graph.computeIfAbsent(child, key -> new LinkedHashSet<>()).add(parent);
        }
        return Map.copyOf(graph);
    }

    /**
     * Indexes every type appearing anywhere in the graph, as a child or as a parent.
     *
     * <p>Indexing parents matters too: {@code java.lang.AutoCloseable} is never a child, so a
     * child-only index would make it impossible to use as a target type.
     */
    private static Map<String, Set<String>> indexBySimpleName() {
        Map<String, Set<String>> index = new HashMap<>();
        for (String type : DIRECT_SUPERTYPES.keySet()) {
            indexSimpleName(index, type);
        }
        for (Set<String> supertypes : DIRECT_SUPERTYPES.values()) {
            for (String type : supertypes) {
                indexSimpleName(index, type);
            }
        }
        return Map.copyOf(index);
    }

    private static void indexSimpleName(Map<String, Set<String>> index, String type) {
        String simple = type.substring(type.lastIndexOf('.') + 1);
        index.computeIfAbsent(simple, key -> new LinkedHashSet<>()).add(type);
    }

    /** Exposed for the test that asserts the table matches the real JDK. */
    public static Set<String> knownTypes() {
        return DIRECT_SUPERTYPES.keySet();
    }

    /** Exposed for the test that asserts the table matches the real JDK. */
    public static Set<String> declaredSupertypesOf(String fqn) {
        return DIRECT_SUPERTYPES.getOrDefault(fqn, Set.of());
    }
}
