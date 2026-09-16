package dev.abgleich.application.port.out;

/** The broker is unreachable or refused an event. The event stays in the outbox and is tried again (B23, B39). */
public final class EventPublishingException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public EventPublishingException(String message, Throwable cause) {
        super(message, cause);
    }
}
