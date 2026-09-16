package dev.abgleich.adapter.in.rest;

/** An {@code Idempotency-Key} sent again with a different invoice: nothing was stored. */
final class IdempotencyKeyReusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    IdempotencyKeyReusedException(String message) {
        super(message);
    }
}
