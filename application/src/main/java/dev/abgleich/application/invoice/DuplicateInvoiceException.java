package dev.abgleich.application.invoice;

/** The database already holds an invoice with this number (B24). */
public final class DuplicateInvoiceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateInvoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
