package io.github.brewlint.core.engine;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("GlobPattern: exclude matching")
class GlobPatternTest {

    @ParameterizedTest
    @CsvSource({
            "**/generated/**,              src/main/generated/Foo.java,      true",
            "**/generated/**,              src/main/java/com/example/Foo.java, false",
            "**/generated/**,              generated/Foo.java,               true",
            "**/target/**,                 target/classes/Foo.class.java,    true",
            "**/target/**,                 src/targetish/Foo.java,           false",
            "**/*.java,                    src/main/java/Foo.java,          true",
            "**/*.java,                    src/main/java/Foo.kt,            false",
            "src/**,                       src/a/b/c/Foo.java,              true",
            "src/**,                       other/a/Foo.java,                false",
            "**/Legacy*.java,              src/LegacyOrder.java,            true",
            "**/Legacy*.java,              src/OrderLegacy.java,            false",
            "*.java,                       Foo.java,                        true",
            "*.java,                       a/Foo.java,                      false",
            "**/Foo?.java,                 src/Foo1.java,                   true",
            "**/Foo?.java,                 src/Foo12.java,                  false",
    })
    @DisplayName("matches the documented glob syntax")
    void matches(String glob, String path, boolean expected) {
        assertThat(GlobPattern.compile(glob).matches(path)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "src/main/java/com/example/OrderService.java",
            "src/main/java/com/example/Payment.java",
    })
    @DisplayName("a pattern that does not apply leaves the path alone")
    void doesNotOverExclude(String path) {
        assertThat(GlobPattern.compile("**/generated/**").matches(path)).isFalse();
    }

    @Test
    @DisplayName("dots in a path are literal, not regex wildcards")
    void dotsAreLiteral() {
        // Without escaping, "com.example" would match "comXexample".
        assertThat(GlobPattern.compile("**/com.example/**").matches("src/com.example/Foo.java")).isTrue();
        assertThat(GlobPattern.compile("**/com.example/**").matches("src/comXexample/Foo.java")).isFalse();
    }
}
