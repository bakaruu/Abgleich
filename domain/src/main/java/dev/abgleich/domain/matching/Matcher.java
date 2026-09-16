package dev.abgleich.domain.matching;

import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Decides what to do with one payment. Matching rules are domain logic, not an adapter (B40).
 * Phase F1 has rule R1 only: a structured reference and the outstanding amount must match exactly.
 */
public final class Matcher {

    public ReconciliationDecision decide(PaymentToMatch payment, List<Invoice> invoices, Instant now) {
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(invoices, "invoices");
        Objects.requireNonNull(now, "now");
        if (!payment.direction().canPayInvoices()) {
            return new ReconciliationDecision.NoMatch("Debits never pay invoices");
        }
        if (!isStructured(payment.reference())) {
            return new ReconciliationDecision.NoMatch("The payment has no QR or creditor reference");
        }
        List<Invoice> candidates = invoices.stream()
                .filter(invoice -> invoice.status().acceptsPayments())
                .filter(invoice -> invoice.creditorAccount().equals(payment.account()))
                .filter(invoice -> invoice.reference().equals(payment.reference()))
                .filter(invoice -> invoice.amount().hasSameCurrencyAs(payment.amount()))
                .filter(invoice -> invoice.outstanding().equals(payment.amount()))
                // Invoices can arrive in any order; the proposals must not (B28).
                .sorted(Comparator.comparing(invoice -> invoice.number().value()))
                .toList();
        if (candidates.isEmpty()) {
            return new ReconciliationDecision.NoMatch("No open invoice has this reference and outstanding amount");
        }
        List<Allocation> proposals = candidates.stream()
                .map(invoice -> Allocation.propose(payment.transactionId(), invoice.id(), payment.amount(),
                        MatchRule.R1, Confidence.CERTAIN, explanation(payment, invoice, candidates.size()), now))
                .toList();
        return AutoConfirmPolicy.decide(MatchRule.R1, proposals, now);
    }

    private static boolean isStructured(PaymentReference reference) {
        return reference instanceof PaymentReference.Qrr || reference instanceof PaymentReference.Scor;
    }

    private static String explanation(PaymentToMatch payment, Invoice invoice, int candidates) {
        String kind = payment.reference() instanceof PaymentReference.Qrr qrr
                ? "QR reference " + qrr.value()
                : "Creditor reference " + ((PaymentReference.Scor) payment.reference()).value();
        String match = kind + " and amount " + payment.amount() + " match invoice " + invoice.number() + " exactly";
        return candidates == 1
                ? match
                : match + ", but " + candidates + " open invoices match equally: choose one";
    }
}
