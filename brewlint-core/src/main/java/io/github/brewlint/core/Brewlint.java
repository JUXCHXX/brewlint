package io.github.brewlint.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Identity of the tool: name and version, as stamped at build time. */
public final class Brewlint {

    public static final String NAME = "brewlint";

    private static final String VERSION_RESOURCE = "/brewlint-version.properties";
    private static final String FALLBACK_VERSION = "0.0.0-dev";

    private static final String VERSION = readVersion();

    private Brewlint() {
    }

    public static String version() {
        return VERSION;
    }

    private static String readVersion() {
        try (InputStream input = Brewlint.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (input == null) {
                return FALLBACK_VERSION;
            }
            Properties properties = new Properties();
            properties.load(input);
            return properties.getProperty("version", FALLBACK_VERSION);
        } catch (IOException exception) {
            return FALLBACK_VERSION;
        }
    }
}
