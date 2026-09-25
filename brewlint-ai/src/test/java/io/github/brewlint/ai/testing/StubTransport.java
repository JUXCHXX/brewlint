package io.github.brewlint.ai.testing;

import io.github.brewlint.ai.transport.HttpTransport;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A transport that answers from a script instead of the network.
 *
 * <p>Every test in this module uses one of these. That is the point: the providers can be tested for
 * the thing that actually breaks in practice, which is the shape of a response, without a network,
 * an API key, or a per-token bill. What is not covered here is a live call, and the test names say
 * so rather than pretending otherwise.
 */
public final class StubTransport implements HttpTransport {

    private final List<Call> calls = new ArrayList<>();
    private final int statusCode;
    private final String responseBody;
    private IOException failure;

    private StubTransport(int statusCode, String responseBody) {
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    /** Answers 200 with {@code body}. */
    public static StubTransport responding(String body) {
        return new StubTransport(200, body);
    }

    /** Answers {@code statusCode} with {@code body}, for exercising the failure paths. */
    public static StubTransport failing(int statusCode, String body) {
        return new StubTransport(statusCode, body);
    }

    /** Fails at the transport level, for exercising timeouts and unreachable servers. */
    public static StubTransport unreachable(String message) {
        StubTransport transport = new StubTransport(0, "");
        transport.failure = new IOException(message);
        return transport;
    }

    @Override
    public Response post(String uri, Map<String, String> headers, String jsonBody, Duration timeout)
            throws IOException {
        calls.add(new Call(uri, Map.copyOf(headers), jsonBody, timeout));
        if (failure != null) {
            throw failure;
        }
        return new Response(statusCode, responseBody);
    }

    public List<Call> calls() {
        return List.copyOf(calls);
    }

    public Call onlyCall() {
        if (calls.size() != 1) {
            throw new IllegalStateException("expected exactly one call, got " + calls.size());
        }
        return calls.getFirst();
    }

    /** One recorded request, for asserting on what would have gone over the wire. */
    public record Call(String uri, Map<String, String> headers, String body, Duration timeout) {
    }
}
