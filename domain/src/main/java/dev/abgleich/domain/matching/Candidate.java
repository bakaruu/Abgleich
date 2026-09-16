package dev.abgleich.domain.matching;

import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.money.Money;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** One way a rule would settle a payment: which invoices get how much, and why. */
record Candidate(MatchRule rule, List<Share> shares, String explanation) {

    /**
     * Best first: higher confidence, then the invoice due first, then the lowest invoice number, then
     * fewer invoices. Never the order the database happened to return (B28).
     */
    static final Comparator<Candidate> RANKING = Comparator
            .comparing((Candidate candidate) -> candidate.rule().confidence().value()).reversed()
            .thenComparing(Candidate::earliestDueDate)
            .thenComparing(Candidate::firstInvoiceNumber)
            .thenComparingInt(candidate -> candidate.shares().size());

    Candidate {
        Objects.requireNonNull(rule, "rule");
        shares = List.copyOf(shares);
        Objects.requireNonNull(explanation, "explanation");
        if (shares.isEmpty()) {
            throw new IllegalArgumentException("A candidate settles at least one invoice");
        }
    }

    Set<UUID> invoiceIds() {
        return shares.stream().map(share -> share.invoice().id()).collect(Collectors.toSet());
    }

    LocalDate earliestDueDate() {
        return shares.stream().map(share -> share.invoice().dueDate()).min(Comparator.naturalOrder()).orElseThrow();
    }

    String firstInvoiceNumber() {
        return shares.stream().map(share -> share.invoice().number().value()).min(Comparator.naturalOrder()).orElseThrow();
    }

    /** @param chargesWrittenOff bank charges accepted so that the invoice counts as settled (B07) */
    record Share(Invoice invoice, Money amount, Money chargesWrittenOff) {
        Share {
            Objects.requireNonNull(invoice, "invoice");
            Objects.requireNonNull(amount, "amount");
            chargesWrittenOff = Objects.requireNonNullElseGet(chargesWrittenOff, () -> Money.zero(amount.currency()));
        }

        static Share of(Invoice invoice, Money amount) {
            return new Share(invoice, amount, null);
        }
    }
}
