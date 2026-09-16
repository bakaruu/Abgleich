package dev.abgleich.adapter.out.synthetic.evaluation;

import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.matching.PaymentToMatch;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Labelled payments against a fixed set of invoices. Every case says what a correct reconciliation does,
 * so the matcher's precision and recall can be measured per rule instead of guessed (plan, phase F2).
 */
public record MatchingDataset(List<Invoice> invoices, List<Case> cases) {

    public MatchingDataset {
        invoices = List.copyOf(invoices);
        cases = List.copyOf(cases);
    }

    /** What a case expects. */
    public enum Kind {
        R1_QR_REFERENCE(Outcome.AUTO),
        R1_CREDITOR_REFERENCE(Outcome.AUTO),
        R2_PARTIAL_PAYMENT(Outcome.REVIEW),
        R2_OVERPAYMENT(Outcome.REVIEW),
        R2_BANK_CHARGES(Outcome.REVIEW),
        R2_CLOSED_INVOICE(Outcome.REVIEW),
        R3_REFERENCE_TYPO(Outcome.REVIEW),
        R4_INVOICE_NUMBER_IN_TEXT(Outcome.REVIEW),
        R5_PAYER_NAME(Outcome.REVIEW),
        R6_SEVERAL_INVOICES(Outcome.REVIEW),
        TIE(Outcome.REVIEW),
        NO_INVOICE(Outcome.NONE),
        LOOKALIKE_NO_INVOICE(Outcome.NONE),
        DEBIT(Outcome.NONE);

        private final Outcome expected;

        Kind(Outcome expected) {
            this.expected = expected;
        }

        public Outcome expected() {
            return expected;
        }
    }

    public enum Outcome { AUTO, REVIEW, NONE }

    /**
     * @param acceptable the invoice sets a correct decision may settle; empty when the payment belongs to no
     *     invoice. A tie accepts each of its equally likely invoices.
     */
    public record Case(String id, Kind kind, PaymentToMatch payment, Set<Set<UUID>> acceptable) {
        public Case {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(payment, "payment");
            acceptable = Set.copyOf(acceptable);
        }
    }
}
