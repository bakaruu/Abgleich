package dev.abgleich.application.port.out;

/**
 * Storage failed for a technical reason: database down, timeout, unexpected constraint. Adapters
 * raise it instead of driver or framework exceptions so the core never depends on them (B39).
 */
public final class StorageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}
