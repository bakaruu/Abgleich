package dev.abgleich.application.invoice;

import java.util.Objects;
import java.util.UUID;

/**
 * What happened to an invoice received as a message.
 *
 * @param invoiceId the stored invoice: the new one, or the one that already had the number or the id
 * @param redelivered the message had been processed before; nothing changed now and the outcome is the
 *     one of the first processing (B24)
 */
public record InvoiceReceipt(Outcome outcome, UUID invoiceId, boolean redelivered) {

    public enum Outcome {
        REGISTERED,
        /** Another message already registered an invoice with this number; nothing was stored. */
        DUPLICATE_NUMBER,
        /** The message id was used before for an invoice with a different number; nothing was stored. */
        ID_REUSED
    }

    public InvoiceReceipt {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(invoiceId, "invoiceId");
        if (outcome == Outcome.ID_REUSED && !redelivered) {
            throw new IllegalArgumentException("A reused id is always a message seen before");
        }
    }
}
