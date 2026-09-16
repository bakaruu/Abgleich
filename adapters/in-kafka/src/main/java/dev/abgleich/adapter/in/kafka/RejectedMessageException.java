package dev.abgleich.adapter.in.kafka;

/**
 * A message that will never be processed, however often it is retried: malformed JSON, a missing field, an
 * invalid invoice. The error handler does not retry it and sends it to the dead letter topic.
 */
public final class RejectedMessageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RejectedMessageException(String message) {
        super(message);
    }

    public RejectedMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
