package com.example.broken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * FIXTURE. Deliberately broken: streams returned by {@code java.nio.file.Files} hold an open handle
 * until closed, and consuming them is not the same as closing them.
 *
 * <p>Expected: RES002 on the four unclosed calls, and nothing on the correct methods.
 */
public class LogImporter {

    /** RES002: assigned to a local, never closed. */
    public long countLines(Path path) throws IOException {
        var lines = Files.lines(path);
        return lines.count();
    }

    /** RES002: walk opens a directory handle and a handle per file visited. */
    public long countEntries(Path root) throws IOException {
        var paths = Files.walk(root);
        return paths.filter(Files::isRegularFile).count();
    }

    /** RES002: list opens the directory. */
    public List<Path> entries(Path dir) throws IOException {
        var stream = Files.list(dir);
        return stream.toList();
    }

    /** RES002: explicitly typed, and chained. Chaining does not close anything. */
    public void forEachLine(Path path) throws IOException {
        Stream<String> lines = Files.lines(path);
        lines.filter(line -> !line.isBlank()).forEach(System.out::println);
    }

    /** Correct: try-with-resources is the documented fix. */
    public long countCorrectly(Path path) throws IOException {
        try (var lines = Files.lines(path)) {
            return lines.count();
        }
    }

    /** Correct: an in-memory stream holds no handle, so there is nothing to close. */
    public List<String> inMemory() {
        var stream = List.of("a", "b").stream();
        return stream.toList();
    }

    /** Correct: a Files method that returns no stream handle. */
    public boolean exists(Path path) {
        return Files.exists(path);
    }
}
