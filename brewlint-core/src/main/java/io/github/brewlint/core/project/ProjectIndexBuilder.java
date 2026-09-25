package io.github.brewlint.core.project;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a {@link ProjectIndex} in one pass over the sources.
 *
 * <p><strong>Why a second parse.</strong> Rules need the index before they can analyse the first
 * file, and the index needs every file, so a single pass cannot supply both without holding every
 * AST in memory. Parsing twice keeps memory flat at the cost of roughly doubling parse time on a run
 * that needs the index.
 *
 * <p>That cost is only paid when a rule asks for it. {@link #isNeededFor} checks whether any enabled
 * rule requires the index, so a scan with only the single-file rules parses once.
 */
public final class ProjectIndexBuilder {

    private ProjectIndexBuilder() {
    }

    /** True when at least one of these rules needs a project-wide view. */
    public static boolean isNeededFor(
            List<io.github.brewlint.core.rule.Rule> rules,
            java.util.function.Predicate<String> isRuleEnabled) {
        return rules.stream()
                .anyMatch(rule -> rule.requiresProjectIndex() && isRuleEnabled.test(rule.id()));
    }

    /**
     * Parses every file once and returns what the sources declare.
     *
     * <p>Files that fail to parse are skipped. The rules that need the index are the ones most
     * likely to run on messy input, and a missing declaration shows up as a missed finding rather
     * than a wrong one.
     */
    public static ProjectIndex build(List<Path> files) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setCharacterEncoding(StandardCharsets.UTF_8));

        Map<String, List<TypeInfo>> bySimpleName = new LinkedHashMap<>();
        Map<String, TypeInfo> byQualifiedName = new LinkedHashMap<>();

        for (Path file : files) {
            ParseResult<CompilationUnit> parsed;
            try {
                parsed = parser.parse(file);
            } catch (IOException | RuntimeException exception) {
                continue;
            }
            parsed.getResult().ifPresent(compilationUnit ->
                    record(compilationUnit, bySimpleName, byQualifiedName));
        }

        return new ProjectIndex(bySimpleName, byQualifiedName);
    }

    private static void record(
            CompilationUnit compilationUnit,
            Map<String, List<TypeInfo>> bySimpleName,
            Map<String, TypeInfo> byQualifiedName) {
        for (TypeDeclaration<?> declaration : compilationUnit.findAll(TypeDeclaration.class)) {
            if (declaration instanceof NodeWithAnnotations<?> withAnnotations) {
                Set<String> annotationNames = new LinkedHashSet<>();
                withAnnotations.getAnnotations()
                        .forEach(annotation -> annotationNames.add(annotation.getName().getIdentifier()));

                String qualifiedName = declaration.getFullyQualifiedName().orElse(declaration.getNameAsString());
                TypeInfo info = new TypeInfo(
                        qualifiedName,
                        declaration.getNameAsString(),
                        annotationNames,
                        findScope(withAnnotations));

                bySimpleName
                        .computeIfAbsent(declaration.getNameAsString(), key -> new ArrayList<>())
                        .add(info);
                byQualifiedName.putIfAbsent(qualifiedName, info);
            }
        }
    }

    /**
     * Reads the scope from either {@code @Scope("prototype")} or the {@code scope} attribute of a
     * stereotype, and returns {@code null} when the type declares none.
     */
    private static String findScope(NodeWithAnnotations<?> declaration) {
        for (var annotation : declaration.getAnnotations()) {
            if (annotation instanceof SingleMemberAnnotationExpr single) {
                if ("Scope".equals(single.getName().getIdentifier())) {
                    return stringValue(single.getMemberValue());
                }
            } else if (annotation instanceof NormalAnnotationExpr normal) {
                for (MemberValuePair pair : normal.getPairs()) {
                    if ("scope".equals(pair.getNameAsString())) {
                        return stringValue(pair.getValue());
                    }
                }
            }
        }
        return null;
    }

    /** A scope is only usable if it is written as a string literal; anything else is ignored. */
    private static String stringValue(Expression value) {
        return value.isStringLiteralExpr() ? value.asStringLiteralExpr().asString() : null;
    }

    /**
     * Builds an index from source text instead of files, for tests that need to state exactly what
     * the rest of the project looks like.
     */
    public static ProjectIndex buildFromSources(List<String> sources) {
        Map<String, List<TypeInfo>> bySimpleName = new LinkedHashMap<>();
        Map<String, TypeInfo> byQualifiedName = new LinkedHashMap<>();
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE));
        for (String source : sources) {
            Optional<CompilationUnit> parsed = parser.parse(source).getResult();
            parsed.ifPresent(compilationUnit -> record(compilationUnit, bySimpleName, byQualifiedName));
        }
        return new ProjectIndex(bySimpleName, byQualifiedName);
    }
}
