package hk.uwu.spectrumapi;

/** A discovery, transport, protocol, or lifecycle failure. */
public final class SpectrumException extends RuntimeException {
    public enum Reason {
        DISCOVERY_UNAVAILABLE, SERVICE_UNAVAILABLE,
        PROTOCOL_MISMATCH, INVALID_STREAM, TIMEOUT, CLOSED, REMOTE_ERROR
    }

    private final Reason reason;

    public SpectrumException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public SpectrumException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason getReason() { return reason; }
}
