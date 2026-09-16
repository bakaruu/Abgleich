package dev.abgleich.domain.invoice;

/** An invoice that breaks a business rule. The message is safe to show to a user. */
public final class InvalidInvoiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public InvalidInvoiceException(String message) {
        super(message);
    }
}
