package dev.abgleich.domain.invoice;

public enum InvoiceStatus {
    OPEN,
    PARTIALLY_PAID,
    PAID,
    OVERPAID,
    CANCELLED;

    /** Whether the invoice still expects money and can be matched. */
    public boolean acceptsPayments() {
        return this == OPEN || this == PARTIALLY_PAID;
    }
}
