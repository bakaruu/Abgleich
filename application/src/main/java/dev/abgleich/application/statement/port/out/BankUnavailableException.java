package dev.abgleich.application.statement.port.out;

/** The bank API could not be reached, timed out or refused the request (B39). Nothing was imported. */
public final class BankUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public BankUnavailableException(String message) {
        super(message);
    }

    public BankUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
