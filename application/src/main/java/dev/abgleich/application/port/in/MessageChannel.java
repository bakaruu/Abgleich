package dev.abgleich.application.port.in;

/** Where a message with its own id came from. Ids are unique per channel. */
public enum MessageChannel {
    /** An {@code InvoiceCreated} event; the id is its event id. */
    KAFKA,
    /** A REST request; the id is its {@code Idempotency-Key} header. */
    REST
}
