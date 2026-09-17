package dev.abgleich.application;

/**
 * Something changed between reading and writing: the version no longer matches, or another active
 * allocation exists. Nothing was written; the caller reloads and decides again (B22, B33).
 */
public final class StaleDataException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StaleDataException(String message) {
        super(message);
    }

    public StaleDataException(String message, Throwable cause) {
        super(message, cause);
    }
}
