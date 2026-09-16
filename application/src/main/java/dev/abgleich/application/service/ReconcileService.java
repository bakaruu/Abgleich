package dev.abgleich.application.service;

import dev.abgleich.application.port.in.ReconcileUseCase;
import dev.abgleich.application.port.in.ReconciliationRun;
import dev.abgleich.application.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.port.out.ReconciliationRepositoryPort;
import dev.abgleich.application.port.out.ReconciliationRepositoryPort.ReversedPayment;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.Matcher;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.matching.ReconciliationDecision;
import dev.abgleich.domain.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Decides each pending payment separately, reversals first. When a concurrent run changed the payment
 * or an invoice, the write is refused (B22); the payment is reloaded and decided again with fresh data,
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

    private enum Outcome { AUTO_CONFIRMED, SENT_TO_REVIEW, UNMATCHED, HANDLED_ELSEWHERE, DEFERRED, REVERSED }

    @Override
    public ReconciliationRun reconcilePending(Iban account) {
        int[] counts = new int[Outcome.values().length];
        for (PaymentToMatch reversal : reconciliations.findPendingReversals(account)) {
            if (reverse(reversal)) {
                counts[Outcome.REVERSED.ordinal()]++;
            }
        }
        List<PaymentToMatch> payments = reconciliations.findPendingCredits(account);
        for (PaymentToMatch payment : payments) {
            counts[reconcile(payment).ordinal()]++;
        }
        return new ReconciliationRun(payments.size(),
                counts[Outcome.AUTO_CONFIRMED.ordinal()], counts[Outcome.SENT_TO_REVIEW.ordinal()],
                counts[Outcome.UNMATCHED.ordinal()], counts[Outcome.HANDLED_ELSEWHERE.ordinal()],
                counts[Outcome.DEFERRED.ordinal()], counts[Outcome.REVERSED.ordinal()]);
    }

    private Outcome reconcile(PaymentToMatch firstRead) {
        PaymentToMatch payment = firstRead;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            List<Invoice> candidates = invoices.findCandidates(payment.account(), payment.amount().currency(),
                    payment.reference());
            ReconciliationDecision decision = matcher.decide(payment, candidates,
                    reconciliations.rejectedInvoiceSets(payment.transactionId()), clock.instant());
            try {
                return record(payment, decision, candidates);
            } catch (StaleDataException stale) {
                Optional<PaymentToMatch> reloaded = reconciliations.findPendingCredit(payment.transactionId());
                if (reloaded.isEmpty()) {
                    return Outcome.HANDLED_ELSEWHERE;
                }
                payment = reloaded.get();
            }
        }
        return Outcome.DEFERRED;
    }

    private Outcome record(PaymentToMatch payment, ReconciliationDecision decision, List<Invoice> candidates) {
        return switch (decision) {
            case ReconciliationDecision.NoMatch noMatch -> Outcome.UNMATCHED;
            case ReconciliationDecision.AutoConfirmed confirmed -> {
                List<Invoice> settled = settle(confirmed.allocations(), candidates);
                reconciliations.recordConfirmed(payment, confirmed.allocations(), settled,
                        InvoiceEvents.between(candidates, settled, clock.instant()));
                yield Outcome.AUTO_CONFIRMED;
            }
            case ReconciliationDecision.NeedsReview review -> {
                reconciliations.recordProposals(payment, review.proposals());
                yield Outcome.SENT_TO_REVIEW;
            }
        };
    }

    /** B10: a bank reversal undoes the payment it reverses, with a note on every allocation it reverses. */
    private boolean reverse(PaymentToMatch reversal) {
        Optional<ReversedPayment> original = reconciliations.findReversedCredit(reversal);
        if (original.isEmpty()) {
            return false;
        }
        Instant now = clock.instant();
        String note = "Reversed by the bank on " + reversal.bookingDate() + " (" + reversal.amount() + ")";
        List<Allocation> reversed = original.get().allocations().stream()
                .map(allocation -> allocation.reverse(Allocation.SYSTEM, now, note))
                .toList();
        Map<UUID, Invoice> byId = original.get().invoices().stream()
                .collect(Collectors.toMap(Invoice::id, Function.identity()));
        List<Invoice> reopened = reversed.stream()
                .map(allocation -> byId.get(allocation.invoiceId()).withReversedPayment(allocation.settledAmount()))
                .toList();
        try {
            reconciliations.recordReversal(reversal, original.get(), reversed, reopened,
                    InvoiceEvents.between(original.get().invoices(), reopened, now));
            return true;
        } catch (StaleDataException stale) {
            return false;
        }
    }

    /** The invoices after applying the confirmed allocations, each one from the version that was read. */
    static List<Invoice> settle(List<Allocation> confirmed, List<Invoice> invoices) {
        Map<UUID, Invoice> byId = invoices.stream().collect(Collectors.toMap(Invoice::id, Function.identity()));
        List<Invoice> settled = new ArrayList<>();
        for (Allocation allocation : confirmed) {
            Invoice invoice = Objects.requireNonNull(byId.get(allocation.invoiceId()), "allocated invoice was not read");
            Money settledAmount = allocation.settledAmount();
            settled.add(invoice.withConfirmedPayment(settledAmount));
        }
        return settled;
    }
}
