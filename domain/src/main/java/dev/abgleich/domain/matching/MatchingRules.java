package dev.abgleich.domain.matching;

import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.matching.Candidate.Share;
import dev.abgleich.domain.matching.text.InvoiceMentions;
import dev.abgleich.domain.matching.text.TextSimilarity;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Rules R1 to R6. Each one only proposes candidates; ranking and the auto-confirm policy decide. */
final class MatchingRules {

    private final MatchingPolicy policy;

    MatchingRules(MatchingPolicy policy) {
        this.policy = policy;
    }

    List<Candidate> candidates(PaymentToMatch payment, List<Invoice> invoices) {
        List<Candidate> candidates = new ArrayList<>();
        candidates.addAll(structuredReference(payment, invoices));
        candidates.addAll(referenceWithTypo(payment, invoices));
        candidates.addAll(invoiceNumberInText(payment, invoices));
        candidates.addAll(payerName(payment, invoices));
        candidates.addAll(severalInvoices(payment, invoices));
        return candidates;
    }

    /** R1, R2 and payments to closed invoices (B31), all driven by an exact QRR or SCOR reference. */
    private List<Candidate> structuredReference(PaymentToMatch payment, List<Invoice> invoices) {
        if (!isStructured(payment.reference())) {
            return List.of();
        }
        String reference = describe(payment.reference());
        List<Candidate> candidates = new ArrayList<>();
        for (Invoice invoice : invoices) {
            if (!invoice.reference().equals(payment.reference())) {
                continue;
            }
            Money amount = payment.amount();
            InvoiceStatus status = invoice.status();
            if (!status.acceptsPayments()) {
                String problem = status == InvoiceStatus.CANCELLED
                        ? "which is cancelled: refund the payment or re-issue the invoice"
                        : "which is already " + status.name().toLowerCase(Locale.ROOT) + ": possible duplicate payment";
                candidates.add(new Candidate(MatchRule.R2, List.of(Share.of(invoice, amount)),
                        reference + " belongs to invoice " + invoice.number() + ", " + problem));
                continue;
            }
            Money outstanding = invoice.outstanding();
            int comparison = amount.compareTo(outstanding);
            if (comparison == 0) {
                candidates.add(new Candidate(MatchRule.R1, List.of(Share.of(invoice, amount)),
                        reference + " and amount " + amount + " match invoice " + invoice.number() + " exactly"));
            } else if (comparison < 0) {
                Money shortfall = outstanding.subtract(amount);
                if (isBankCharges(payment, outstanding, shortfall)) {
                    candidates.add(new Candidate(MatchRule.R2, List.of(new Share(invoice, amount, shortfall)),
                            reference + " matches invoice " + invoice.number() + "; the payment is " + shortfall
                                    + " short, which looks like bank charges: confirming settles the invoice"));
                } else {
                    candidates.add(new Candidate(MatchRule.R2, List.of(Share.of(invoice, amount)),
                            reference + " matches invoice " + invoice.number() + "; partial payment, " + shortfall
                                    + " would stay open"));
                }
            } else {
                candidates.add(new Candidate(MatchRule.R2, List.of(Share.of(invoice, amount)),
                        reference + " matches invoice " + invoice.number() + "; the payment is "
                                + amount.subtract(outstanding) + " more than outstanding: the invoice would be overpaid"));
            }
        }
        return candidates;
    }

    /**
     * B07: foreign payments often arrive a few francs or euros short. Reported charges that explain the
     * difference exactly, or a small difference within the policy, count as charges, never as a partial
     * payment of an invoice that is in fact settled. Still only a proposal.
     */
    private boolean isBankCharges(PaymentToMatch payment, Money outstanding, Money shortfall) {
        if (payment.charges() != null && payment.charges().equals(shortfall)) {
            return true;
        }
        BigDecimal ratioLimit = outstanding.amount().multiply(policy.maxChargesRatio()).setScale(2, RoundingMode.DOWN);
        BigDecimal limit = ratioLimit.min(policy.maxChargesAmount());
        return shortfall.amount().compareTo(limit) <= 0;
    }

    /** R3: a mistyped reference one character away from an open invoice's reference, exact amount (B04). */
    private List<Candidate> referenceWithTypo(PaymentToMatch payment, List<Invoice> invoices) {
        if (!(payment.reference() instanceof PaymentReference.FreeText typed)) {
            return List.of();
        }
        String compactTyped = typed.value().replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        return open(invoices).stream()
                .filter(invoice -> isStructured(invoice.reference()))
                .filter(invoice -> invoice.outstanding().equals(payment.amount()))
                .filter(invoice -> TextSimilarity.withinOneEdit(compactTyped, referenceText(invoice.reference())))
                .map(invoice -> new Candidate(MatchRule.R3, List.of(Share.of(invoice, payment.amount())),
                        "Reference " + typed.value() + " is one character away from " + describe(invoice.reference())
                                + " of invoice " + invoice.number() + "; amount " + payment.amount() + " matches"))
                .toList();
    }

    /** R4: the invoice number appears in the remittance text or free reference, exact amount. */
    private List<Candidate> invoiceNumberInText(PaymentToMatch payment, List<Invoice> invoices) {
        String text = payment.searchableText();
        return open(invoices).stream()
                .filter(invoice -> invoice.outstanding().equals(payment.amount()))
                .filter(invoice -> InvoiceMentions.mentions(text, invoice.number()))
                .map(invoice -> new Candidate(MatchRule.R4, List.of(Share.of(invoice, payment.amount())),
                        "Invoice number " + invoice.number() + " appears in the remittance text; amount "
                                + payment.amount() + " matches"))
                .toList();
    }

    /** R5: a payer name similar to the debtor, exact or partial amount, booked near the due date. */
    private List<Candidate> payerName(PaymentToMatch payment, List<Invoice> invoices) {
        if (payment.counterpartyName() == null) {
            return debtorNameInText(payment, invoices);
        }
        List<Candidate> candidates = new ArrayList<>();
        Similarities similarities = new Similarities(payment.counterpartyName());
        for (Invoice invoice : open(invoices)) {
            // The cheap conditions first: comparing two names costs far more than a date or an amount, and with a
            // large open ledger this rule would otherwise compare every name on every payment.
            long days = Math.abs(ChronoUnit.DAYS.between(invoice.dueDate(), payment.bookingDate()));
            int comparison = payment.amount().compareTo(invoice.outstanding());
            if (days > policy.dueDateWindowDays() || comparison > 0) {
                continue;
            }
            BigDecimal similarity = similarities.of(invoice.debtorName());
            if (similarity.compareTo(policy.nameThreshold()) < 0) {
                continue;
            }
            String amountText = comparison == 0
                    ? "amount " + payment.amount() + " matches"
                    : "partial payment, " + invoice.outstanding().subtract(payment.amount()) + " would stay open";
            candidates.add(new Candidate(MatchRule.R5, List.of(Share.of(invoice, payment.amount())),
                    "Payer name is similar to the debtor of invoice " + invoice.number() + " (" + similarity
                            + "); " + amountText + "; booked " + days + " days from the due date"));
        }
        return candidates;
    }

    /**
     * R5 for files without a payer field: Norma 43 writes the ordering party inside the concept ("TRANSF
     * JOSE MUÑOZ ALVAREZ"). The debtor's whole name must appear as consecutive words, and because a text
     * is weaker evidence than a name field, only the exact outstanding amount counts: a company's other
     * payments ("CUOTA MANTENIMIENTO PINTURAS SOL SA") are not proposed as partial payments.
     */
    private List<Candidate> debtorNameInText(PaymentToMatch payment, List<Invoice> invoices) {
        String text = " " + TextSimilarity.nameKey(payment.remittanceText()) + " ";
        if (text.isBlank()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        for (Invoice invoice : open(invoices)) {
            String debtor = TextSimilarity.nameKey(invoice.debtorName());
            long days = Math.abs(ChronoUnit.DAYS.between(invoice.dueDate(), payment.bookingDate()));
            if (debtor.isEmpty() || !text.contains(" " + debtor + " ") || days > policy.dueDateWindowDays()
                    || !payment.amount().equals(invoice.outstanding())) {
                continue;
            }
            candidates.add(new Candidate(MatchRule.R5, List.of(Share.of(invoice, payment.amount())),
                    "The name of the debtor of invoice " + invoice.number() + " appears in the remittance text; amount "
                            + payment.amount() + " matches; booked " + days + " days from the due date"));
        }
        return candidates;
    }

    /** R6: one payment for two to five open invoices of one debtor, found by payer name or invoice numbers. */
    private List<Candidate> severalInvoices(PaymentToMatch payment, List<Invoice> invoices) {
        String text = payment.searchableText();
        Similarities similarities = new Similarities(payment.counterpartyName());
        Map<String, List<Invoice>> byDebtor = new LinkedHashMap<>();
        open(invoices).stream()
                .filter(invoice -> invoice.outstanding().compareTo(payment.amount()) < 0)
                .filter(invoice -> InvoiceMentions.mentions(text, invoice.number())
                        || similarities.of(invoice.debtorName()).compareTo(policy.nameThreshold()) >= 0)
                .sorted(Comparator.comparing(Invoice::dueDate).thenComparing(invoice -> invoice.number().value()))
                .forEach(invoice -> byDebtor.computeIfAbsent(TextSimilarity.nameKey(invoice.debtorName()),
                        debtor -> new ArrayList<>()).add(invoice));

        List<Candidate> candidates = new ArrayList<>();
        for (List<Invoice> debtorInvoices : byDebtor.values()) {
            if (debtorInvoices.size() < 2) {
                continue;
            }
            SubsetSearch search = new SubsetSearch(policy.maxInvoicesInCombination(), policy.combinationBudget(), 3);
            for (List<Invoice> group : search.find(debtorInvoices, payment.amount(), policy.maxInvoicesSearched())) {
                List<Share> shares = group.stream().map(invoice -> Share.of(invoice, invoice.outstanding())).toList();
                String numbers = String.join(", ", group.stream().map(invoice -> invoice.number().value()).toList());
                candidates.add(new Candidate(MatchRule.R6, shares, "One payment of " + payment.amount() + " settles "
                        + group.size() + " invoices of the same debtor exactly: " + numbers));
            }
        }
        return candidates;
    }

    /**
     * Comparing two company names is the most expensive thing a rule does, and one ledger holds the same debtor
     * names over and over, so each pair is compared once per payment. A payment without a payer name never
     * matches by name, which the constant answer expresses.
     */
    private static final class Similarities {

        private final String payer;
        private final Map<String, BigDecimal> known = new HashMap<>();

        private Similarities(String payer) {
            this.payer = payer;
        }

        private BigDecimal of(String debtorName) {
            if (payer == null) {
                return BigDecimal.ZERO;
            }
            return known.computeIfAbsent(debtorName, name -> TextSimilarity.nameSimilarity(payer, name));
        }
    }

    private static List<Invoice> open(List<Invoice> invoices) {
        return invoices.stream().filter(invoice -> invoice.status().acceptsPayments()).toList();
    }

    private static boolean isStructured(PaymentReference reference) {
        return reference instanceof PaymentReference.Qrr || reference instanceof PaymentReference.Scor;
    }

    private static String describe(PaymentReference reference) {
        return switch (reference) {
            case PaymentReference.Qrr qrr -> "QR reference " + qrr.value();
            case PaymentReference.Scor scor -> "Creditor reference " + scor.value();
            case PaymentReference.FreeText text -> "Reference " + text.value();
            case PaymentReference.None none -> "No reference";
        };
    }

    private static String referenceText(PaymentReference reference) {
        return switch (reference) {
            case PaymentReference.Qrr qrr -> qrr.value().value();
            case PaymentReference.Scor scor -> scor.value().value();
            case PaymentReference.FreeText text -> text.value();
            case PaymentReference.None none -> "";
        };
    }
}
