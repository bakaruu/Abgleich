package dev.abgleich.application.service;

import dev.abgleich.application.port.in.ReconcileUseCase;
import dev.abgleich.application.port.in.ReconciliationRun;
import dev.abgleich.application.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.port.out.ReconciliationRepositoryPort;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.matching.Matcher;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.matching.ReconciliationDecision;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides each unmatched credit separately. When a concurrent run changed the payment or the
 * invoice, the write is refused (B22); the payment is reloaded and decided again with fresh data,
 * a bounded number of times.
 */
public final class ReconcileService implements ReconcileUseCase {

    static final int MAX_ATTEMPTS = 3;

    private final ReconciliationRepositoryPort reconciliations;
    private final InvoiceRepositoryPort invoices;
    private final Matcher matcher;
    private final Clock clock;

    public ReconcileService(ReconciliationRepositoryPort reconciliations, InvoiceRepositoryPort invoices,
            Matcher matcher, Clock clock) {
        this.reconciliations = Objects.requireNonNull(reconciliations, "reconciliations");
        this.invoices = Objects.requireNonNull(invoices, "invoices");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    private enum Outcome { AUTO_CONFIRMED, SENT_TO_REVIEW, UNMATCHED, HANDLED_ELSEWHERE, DEFERRED }

    @Override
    public ReconciliationRun reconcilePending(Iban account) {
        List<PaymentToMatch> payments = reconciliations.findUnmatchedCredits(account);
        int[] counts = new int[Outcome.values().length];
        for (PaymentToMatch payment : payments) {
            counts[reconcile(payment).ordinal()]++;
        }
        return new ReconciliationRun(payments.size(),
                counts[Outcome.AUTO_CONFIRMED.ordinal()], counts[Outcome.SENT_TO_REVIEW.ordinal()],
                counts[Outcome.UNMATCHED.ordinal()], counts[Outcome.HANDLED_ELSEWHERE.ordinal()],
                counts[Outcome.DEFERRED.ordinal()]);
    }

    private Outcome reconcile(PaymentToMatch firstRead) {
        PaymentToMatch payment = firstRead;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            List<Invoice> open = invoices.findOpen(payment.account(), payment.amount().currency());
            ReconciliationDecision decision = matcher.decide(payment, open, clock.instant());
            try {
                return record(payment, decision, open);
            } catch (StaleDataException stale) {
                Optional<PaymentToMatch> reloaded = reconciliations.findUnmatchedCredit(payment.transactionId());
                if (reloaded.isEmpty()) {
                    return Outcome.HANDLED_ELSEWHERE;
                }
                payment = reloaded.get();
            }
        }
        return Outcome.DEFERRED;
    }

    private Outcome record(PaymentToMatch payment, ReconciliationDecision decision, List<Invoice> open) {
        return switch (decision) {
            case ReconciliationDecision.NoMatch noMatch -> Outcome.UNMATCHED;
            case ReconciliationDecision.AutoConfirmed confirmed -> {
                Invoice paid = open.stream()
                        .filter(invoice -> invoice.id().equals(confirmed.allocation().invoiceId()))
                        .findFirst()
                        .orElseThrow()
                        .withConfirmedPayment(confirmed.allocation().amount());
                reconciliations.recordConfirmed(payment, confirmed.allocation(), paid);
                yield Outcome.AUTO_CONFIRMED;
            }
            case ReconciliationDecision.NeedsReview review -> {
                reconciliations.recordProposals(payment, review.proposals());
                yield Outcome.SENT_TO_REVIEW;
            }
        };
    }
}
