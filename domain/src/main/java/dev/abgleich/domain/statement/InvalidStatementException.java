package dev.abgleich.domain.statement;

import java.util.Objects;

/**
 * A bank statement file that must be rejected as a whole. Parsers throw only this exception,
 * never technical ones such as {@code XMLStreamException} (B39). The message is safe to show to
 * a user: it never contains names, IBANs or text copied from the file (B41).
 */
public final class InvalidStatementException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** Not well-formed, truncated, or a required field is missing or invalid. */
        MALFORMED_FILE,
        /** A known format in a version that is not supported (B13). */
        UNSUPPORTED_VERSION,
        /** Content that is never accepted: DTDs and entities (B08), excessive nesting or text (B42). */
        FORBIDDEN_CONTENT,
        /** Opening balance plus credits minus debits differs from the closing balance (B11). */
        UNBALANCED,
        /** An entry whose transaction details do not add up to the entry amount (B09). */
        INCONSISTENT_ENTRY,
        /** Balances and entries of one statement use different currencies (B06). */
        MIXED_CURRENCIES
    }

    private final Reason reason;

    public InvalidStatementException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public InvalidStatementException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
