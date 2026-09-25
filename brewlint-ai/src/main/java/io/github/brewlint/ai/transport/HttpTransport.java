package io.github.brewlint.ai.transport;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * The one network call this project makes.
 *
 * <p>An interface rather than a direct use of {@code java.net.http.HttpClient} for one reason: the
 * providers must be testable without a network and without an API key. Every test in this module
 * either stubs this or never reaches it, which means a broken response shape is caught by
 * {@code mvn test} on a plane rather than by a paid request from a user's terminal.
 */
public interface HttpTransport extends AutoCloseable {

    /**
     * Sends a POST with a JSON body.
     *
     * @param uri         absolute endpoint
     * @param headers     request headers
     * @param jsonBody    the request body, already serialised
     * @param timeout     how long to wait before giving up
     * @throws IOException on any transport-level failure, including timeout
     */
    Response post(String uri, Map<String, String> headers, String jsonBody, Duration timeout)
            throws IOException;

    /**
     * @param statusCode HTTP status
     * @param body       response body, never null, possibly empty
     */
    record Response(int statusCode, String body) {
    }

    @Override
    default void close() {
        // The JDK client holds no resources that need explicit release before the JVM exits, and a
        // custom implementation may not either.
    }
}
