package dev.abgleich.domain.reference;

/** Raised when a payment reference or IBAN fails format or check-digit validation. */
public final class InvalidReferenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidReferenceException(String message) {
        super(message);
    }
}
