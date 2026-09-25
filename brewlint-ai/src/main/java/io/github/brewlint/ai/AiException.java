package io.github.brewlint.ai;

/**
 * A provider could not do its job.
 *
 * <p>Distinct from a provider that worked and found nothing. The CLI treats this as a warning and
 * still prints the deterministic report, because a scan that loses its AI pass has not lost its
 * rules, and failing the whole build over an optional feature would be the wrong trade.
 */
public class AiException extends Exception {

    private final String provider;

    public AiException(String provider, String message) {
        this(provider, message, null);
    }

    public AiException(String provider, String message, Throwable cause) {
        super(provider + ": " + message, cause);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }
}
