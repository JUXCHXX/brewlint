package io.github.brewlint.core.engine;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Finds the Java source files to analyse. */
public final class SourceCollector {

    private SourceCollector() {
    }

    /**
     * Walks {@code root} and returns every {@code .java} file, sorted for deterministic output.
     *
     * <p>Build output directories are skipped outright rather than left to {@code exclude}: walking
     * a multi-gigabyte {@code target/} directory is pure waste, and a generated file that someone
     * wants checked can be pointed at directly.
     */
    public static List<Path> collectJavaFiles(Path root) throws IOException {
        if (Files.isRegularFile(root)) {
            return root.toString().endsWith(".java") ? List.of(root) : List.of();
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a file or directory: " + root);
        }

        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                String name = directory.getFileName().toString();
                if (!directory.equals(root) && isBuildOutput(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (file.getFileName().toString().endsWith(".java")) {
                    files.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        files.sort(Comparator.comparing(Path::toString));
        return List.copyOf(files);
    }

    private static boolean isBuildOutput(String directoryName) {
        return switch (directoryName) {
            case "target", "build", "out", "node_modules", ".git", ".gradle", ".idea" -> true;
            default -> false;
        };
    }
}
