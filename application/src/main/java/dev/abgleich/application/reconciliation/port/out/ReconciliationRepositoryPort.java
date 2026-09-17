package dev.abgleich.application.reconciliation.port.out;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.PaymentToMatch;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Writes of the reconciliation. Every write checks the versions the caller read and happens in one
 * short transaction: if anything changed, nothing is written and {@link StaleDataException} is thrown (B22).
 */
public interface ReconciliationRepositoryPort {

    /** Unmatched credits of the account, oldest booking first. Debits are never candidates (B10). */
    List<PaymentToMatch> findPendingCredits(Iban account);

    /** The payment if it is still an unmatched credit, with its current version. */
    Optional<PaymentToMatch> findPendingCredit(UUID transactionId);

    /** Invoice sets a person rejected for this payment, so they are not proposed again. */
    Set<Set<UUID>> rejectedInvoiceSets(UUID transactionId);

    /**
     * Marks the payment matched and stores the confirmed allocations, the invoices with their new paid amounts
     * and the events these changes cause, all in one transaction (B23).
     */
    void recordConfirmed(PaymentToMatch payment, List<Allocation> confirmed, List<Invoice> settledInvoices,
            List<InvoiceEvent> events);

    /** Marks the payment as proposed and stores the proposals for review. */
    void recordProposals(PaymentToMatch payment, List<Allocation> proposals);

    /** Unmatched debits the bank marked as reversals (B10). */
    List<PaymentToMatch> findPendingReversals(Iban account);

    /**
     * The matched credit a reversal undoes: same account and amount, same end-to-end id or reference,
     * booked no later. Empty when there is none or more than one, so a reversal never guesses.
     */
    Optional<ReversedPayment> findReversedCredit(PaymentToMatch reversal);

    /** Reverses the original allocations, reopens the invoices, marks both transactions and stores the events (B23). */
    void recordReversal(PaymentToMatch reversal, ReversedPayment original, List<Allocation> reversedAllocations,
            List<Invoice> reopenedInvoices, List<InvoiceEvent> events);

    /** A matched credit with the confirmed allocations and invoices it settled. */
    record ReversedPayment(UUID transactionId, long version, List<Allocation> allocations, List<Invoice> invoices) {
        public ReversedPayment {
            Objects.requireNonNull(transactionId, "transactionId");
            allocations = List.copyOf(allocations);
            invoices = List.copyOf(invoices);
        }
    }
}
