package dev.abgleich.domain.invoice;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What other systems, such as the ERP that issued the invoice, need to know about an invoice: it is now
 * settled, or a reversal made it expect money again. Stored in the same transaction as the change that
 * caused it and published afterwards (B23).
 */
public sealed interface InvoiceEvent permits InvoiceEvent.InvoicePaid, InvoiceEvent.InvoiceReopened {

    /** Unique per event; a consumer that receives an event twice recognises it by this id. */
    UUID eventId();

    UUID invoiceId();

    InvoiceNumber number();

    Iban creditorAccount();

    Money amount();

    Money paidAmount();

    InvoiceStatus status();

    Instant occurredAt();

    /**
     * The event a change of an invoice produces, if any. Only the step into or out of "settled" (paid or
     * overpaid) is announced; a partial payment changes nothing another system has to act on.
     */
    static Optional<InvoiceEvent> between(Invoice before, Invoice after, Supplier<UUID> eventIds, Instant occurredAt) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        if (!before.id().equals(after.id())) {
            throw new IllegalArgumentException("Both states must belong to the same invoice");
        }
        boolean wasSettled = settled(before.status());
        boolean isSettled = settled(after.status());
        if (!wasSettled && isSettled) {
            return Optional.of(new InvoicePaid(eventIds.get(), after.id(), after.number(), after.creditorAccount(),
                    after.amount(), after.paidAmount(), after.status(), occurredAt));
        }
        if (wasSettled && !isSettled) {
            return Optional.of(new InvoiceReopened(eventIds.get(), after.id(), after.number(), after.creditorAccount(),
                    after.amount(), after.paidAmount(), after.status(), occurredAt));
        }
        return Optional.empty();
    }

    private static boolean settled(InvoiceStatus status) {
        return status == InvoiceStatus.PAID || status == InvoiceStatus.OVERPAID;
    }

    /** The invoice received all its money, or more. */
    record InvoicePaid(UUID eventId, UUID invoiceId, InvoiceNumber number, Iban creditorAccount, Money amount,
            Money paidAmount, InvoiceStatus status, Instant occurredAt) implements InvoiceEvent {

        public InvoicePaid {
            requireValid(eventId, invoiceId, number, creditorAccount, amount, paidAmount, status, occurredAt);
            if (!settled(status)) {
                throw new IllegalArgumentException("A paid invoice is PAID or OVERPAID, not " + status);
            }
        }
    }

    /** A settled invoice expects money again, for example after the bank reversed its payment (B10). */
    record InvoiceReopened(UUID eventId, UUID invoiceId, InvoiceNumber number, Iban creditorAccount, Money amount,
            Money paidAmount, InvoiceStatus status, Instant occurredAt) implements InvoiceEvent {

        public InvoiceReopened {
            requireValid(eventId, invoiceId, number, creditorAccount, amount, paidAmount, status, occurredAt);
            if (!status.acceptsPayments()) {
                throw new IllegalArgumentException("A reopened invoice is OPEN or PARTIALLY_PAID, not " + status);
            }
        }
    }

    private static void requireValid(UUID eventId, UUID invoiceId, InvoiceNumber number, Iban creditorAccount,
            Money amount, Money paidAmount, InvoiceStatus status, Instant occurredAt) {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(creditorAccount, "creditorAccount");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(paidAmount, "paidAmount");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (!paidAmount.hasSameCurrencyAs(amount)) {
            throw new IllegalArgumentException("Paid amount must be in the invoice currency");
        }
    }
}
