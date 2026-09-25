package io.github.brewlint.ai.transport;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The real transport, on {@code java.net.http}.
 *
 * <p>That module is baked into the jlink runtime image on purpose. It was added in Hito 3, before
 * anything used it, because a runtime image is closed at build time: omitting it would have produced
 * a binary that worked perfectly until this milestone shipped and then failed for every npm user
 * with a {@code NoClassDefFoundError} that nobody running {@code ./mvnw} could reproduce.
 */
public final class JdkHttpTransport implements HttpTransport {

    private final HttpClient client;

    public JdkHttpTransport() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public JdkHttpTransport(HttpClient client) {
        this.client = client;
    }

    @Override
    public Response post(String uri, Map<String, String> headers, String jsonBody, Duration timeout)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(uri))
                .timeout(timeout)
                .header("content-type", "application/json")
                // A linter is not a browser, and a user agent of "Java/21" invites servers to treat
                // it as a scraper. Being explicit about what this is costs nothing.
                .header("user-agent", "brewlint");

        headers.forEach(request::header);

        HttpRequest built = request
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        try {
            HttpResponse<String> response = client.send(built, HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for " + uri, exception);
        }
    }
}
