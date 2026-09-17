package dev.abgleich.application.statement.port.out;

/**
 * The database refused an import because the same file, or a camt message with the same id, is
 * already stored for the account (B21). Adapters raise it instead of their own exceptions (B39).
 */
public final class DuplicateImportException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DuplicateImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
