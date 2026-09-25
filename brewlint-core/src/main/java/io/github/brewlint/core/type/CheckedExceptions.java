package io.github.brewlint.core.type;

import java.util.Set;

/**
 * Tells a checked exception from an unchecked one, by name.
 *
 * <p>Needed by rules that reason about what a {@code @Transactional} method can throw: Spring rolls
 * back on {@code RuntimeException} and {@code Error} but <em>commits</em> on a checked exception
 * unless the annotation names it in {@code rollbackFor}.
 *
 * <h2>Why this is a whitelist, not a blacklist</h2>
 * The tempting implementation lists the unchecked exceptions and treats everything else as checked.
 * That gets a project's own {@code MyBusinessException} wrong: if it extends
 * {@code RuntimeException} and is not on the list, the rule would demand a {@code rollbackFor} that
 * is not needed, and be right to. Listing the checked exceptions and answering "I do not know" for
 * anything else can only ever miss a finding, never invent one, and a missed finding is a much
 * cheaper mistake for a linter to make.
 *
 * <p>Every name in the list is asserted against the JDK by
 * {@code CheckedExceptionsTest}, so a typo or a wrong claim fails the build.
 */
public final class CheckedExceptions {

    private static final String JAVA_LANG_PREFIX = "java.lang.";

    /**
     * The checked exceptions that actually turn up in Spring service code. Not exhaustive on
     * purpose: adding a name is cheap, and each one is a class worth naming explicitly.
     *
     * <p>Two tempting names are absent because the JDK test proved them wrong:
     * {@code UncheckedIOException} extends {@code RuntimeException} despite the name, and neither
     * {@code TransformerException} nor {@code DataFormatException} exists in the platform at all.
     */
    private static final Set<String> KNOWN_CHECKED = Set.of(
            "Exception",
            "IOException",
            "FileNotFoundException",
            "InterruptedIOException",
            "EOFException",
            "SQLException",
            "TimeoutException",
            "InterruptedException",
            "ExecutionException",
            "ClassNotFoundException",
            "NoSuchMethodException",
            "NoSuchFieldException",
            "IllegalAccessException",
            "InvocationTargetException",
            "ReflectiveOperationException",
            "CloneNotSupportedException",
            "URISyntaxException",
            "MalformedURLException",
            "ParseException",
            "SAXException",
            "NoSuchAlgorithmException",
            "InvalidKeyException",
            "NoSuchPaddingException");

    private CheckedExceptions() {
    }

    /**
     * True only when the name is a checked exception this class is certain about.
     *
     * <p>Anything ending in {@code Error} or {@code RuntimeException} is unchecked, which covers
     * custom unchecked types without having to enumerate them.
     */
    public static boolean isKnownChecked(String typeName) {
        String simple = simpleNameOf(typeName);
        if (simple.isEmpty() || "Throwable".equals(simple)) {
            return false;
        }
        if (simple.endsWith("Error") || simple.endsWith("RuntimeException")) {
            return false;
        }
        return KNOWN_CHECKED.contains(simple);
    }

    private static String simpleNameOf(String typeName) {
        if (typeName == null) {
            return "";
        }
        String name = typeName.trim();
        int genericStart = name.indexOf('<');
        if (genericStart >= 0) {
            name = name.substring(0, genericStart);
        }
        if (name.startsWith(JAVA_LANG_PREFIX)) {
            name = name.substring(JAVA_LANG_PREFIX.length());
        }
        int lastDot = name.lastIndexOf('.');
        return (lastDot >= 0 ? name.substring(lastDot + 1) : name).trim();
    }

    /** Exposed so the test that checks the list against the JDK can read it. */
    public static Set<String> knownChecked() {
        return KNOWN_CHECKED;
    }
}
