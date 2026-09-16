package dev.abgleich.application.port.out;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.PaymentToMatch;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRepositoryPort {

    /** Unmatched credits of the account, oldest booking first. Debits are never candidates (B10). */
    List<PaymentToMatch> findUnmatchedCredits(Iban account);

    /** The payment if it is still an unmatched credit, with its current version. */
    Optional<PaymentToMatch> findUnmatchedCredit(UUID transactionId);

    /**
     * In one transaction: marks the payment matched, stores the confirmed allocation and the invoice
     * with its new paid amount. Both the payment and the invoice must still have the versions they
     * were read at.
     *
     * @throws StaleDataException if either changed meanwhile; nothing is written (B22)
     */
    void recordConfirmed(PaymentToMatch payment, Allocation allocation, Invoice paidInvoice);

    /**
     * In one transaction: marks the payment as proposed and stores the proposals for review.
     *
     * @throws StaleDataException if the payment changed meanwhile; nothing is written (B22)
     */
    void recordProposals(PaymentToMatch payment, List<Allocation> proposals);
}
