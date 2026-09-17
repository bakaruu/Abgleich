package dev.abgleich.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.matching.text.TextSimilarity;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The exact points where a rule changes its mind. Mutation testing found these: every threshold was covered by
 * cases comfortably on one side of it, so changing {@code <=} into {@code <} in the rules broke nothing that the
 * tests could see. Each test here sits on the threshold itself, and its neighbour one step away.
 */
class MatchingBoundariesTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");
    private static final LocalDate BOOKED = LocalDate.of(2026, 9, 15);
    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));
    private static final MatchingPolicy POLICY = MatchingPolicy.defaults();

    private final Matcher matcher = new Matcher();

    /** R2, B07: 2 % of the outstanding amount, capped at 30.00, still counts as bank charges. */
    @Test
    void a_shortfall_exactly_at_the_charges_limit_is_still_bank_charges() {
        Invoice invoice = invoice("F-2026-0500", "1000.00", SCOR, "Keller GmbH", BOOKED.plusDays(10));

        ReconciliationDecision atTheLimit = matcher.decide(scorPayment("980.00"), List.of(invoice), NOW);
        ReconciliationDecision oneCentBeyond = matcher.decide(scorPayment("979.99"), List.of(invoice), NOW);

        assertThat(rules(atTheLimit)).containsExactly(MatchRule.R2);
        assertThat(explanation(atTheLimit)).contains("bank charges");
        assertThat(explanation(oneCentBeyond)).doesNotContain("bank charges");
    }

    /** The cap wins over the ratio: 2 % of 10 000 is 200, but only 30.00 may be written off. */
    @Test
    void the_charges_cap_applies_when_the_ratio_would_allow_more() {
        Invoice invoice = invoice("F-2026-0501", "10000.00", SCOR, "Keller GmbH", BOOKED.plusDays(10));

        ReconciliationDecision atTheCap = matcher.decide(scorPayment("9970.00"), List.of(invoice), NOW);
        ReconciliationDecision beyondTheCap = matcher.decide(scorPayment("9969.99"), List.of(invoice), NOW);

        assertThat(explanation(atTheCap)).contains("bank charges");
        assertThat(explanation(beyondTheCap)).doesNotContain("bank charges");
    }

    /** R5: an invoice due exactly at the edge of the window is still considered; one day further is not. */
    @Test
    void an_invoice_due_exactly_at_the_edge_of_the_window_still_counts() {
        LocalDate lastDay = BOOKED.plusDays(POLICY.dueDateWindowDays());
        Invoice inside = invoice("F-2026-0502", "480.00", PaymentReference.none(), "Keller GmbH", lastDay);
        Invoice outside = invoice("F-2026-0503", "480.00", PaymentReference.none(), "Keller GmbH", lastDay.plusDays(1));

        assertThat(rules(matcher.decide(namedPayment("480.00", "Keller GmbH"), List.of(inside), NOW)))
                .containsExactly(MatchRule.R5);
        assertThat(rules(matcher.decide(namedPayment("480.00", "Keller GmbH"), List.of(outside), NOW)))
                .isEmpty();
    }

    /** R5: a payer name exactly as similar as the threshold demands is accepted, just below it is not. */
    @Test
    void a_payer_name_exactly_at_the_similarity_threshold_is_accepted() {
        String payer = "Keller Elektro AG";
        String debtor = "Keller Elektrotechnik AG";
        BigDecimal similarity = TextSimilarity.nameSimilarity(payer, debtor);
        Invoice invoice = invoice("F-2026-0504", "480.00", PaymentReference.none(), debtor, BOOKED.plusDays(5));

        ReconciliationDecision exactlyEnough = new Matcher(policyWithNameThreshold(similarity))
                .decide(namedPayment("480.00", payer), List.of(invoice), NOW);
        ReconciliationDecision justTooLittle = new Matcher(policyWithNameThreshold(similarity.add(new BigDecimal("0.01"))))
                .decide(namedPayment("480.00", payer), List.of(invoice), NOW);

        assertThat(rules(exactlyEnough)).containsExactly(MatchRule.R5);
        assertThat(rules(justTooLittle)).isEmpty();
    }

    /** R6 combines invoices smaller than the payment; one that equals it on its own belongs to R1 or R2, not R6. */
    @Test
    void an_invoice_as_large_as_the_payment_is_never_part_of_a_combination() {
        Invoice whole = invoice("F-2026-0505", "300.00", PaymentReference.none(), "Keller GmbH", BOOKED.plusDays(5));
        Invoice half = invoice("F-2026-0506", "150.00", PaymentReference.none(), "Keller GmbH", BOOKED.plusDays(5));
        Invoice otherHalf = invoice("F-2026-0507", "150.00", PaymentReference.none(), "Keller GmbH", BOOKED.plusDays(5));

        ReconciliationDecision decision =
                matcher.decide(namedPayment("300.00", "Keller GmbH"), List.of(whole, half, otherHalf), NOW);

        assertThat(rules(decision)).contains(MatchRule.R6);
        assertThat(combinedInvoices(decision))
                .as("the combination is the two halves, never the invoice that already covers the payment alone")
                .containsExactlyInAnyOrder(half.id(), otherHalf.id());
    }

    /**
     * R3 needs both: one edit away and the exact amount. An invoice can only carry a structured reference or
     * none, so the third condition is guarded by {@code Invoice} itself.
     */
    @Test
    void a_mistyped_reference_alone_is_not_enough_for_R3() {
        Invoice invoice = invoice("F-2026-0520", "480.00", SCOR, "Keller GmbH", BOOKED.plusDays(5));

        assertThat(rules(matcher.decide(typedReference("RF18539007547035", "480.00"), List.of(invoice), NOW)))
                .as("one character away and the exact amount")
                .containsExactly(MatchRule.R3);
        assertThat(rules(matcher.decide(typedReference("RF18539007547035", "479.99"), List.of(invoice), NOW)))
                .as("the amount must be exact")
                .isEmpty();
        assertThat(rules(matcher.decide(typedReference("RF18539007547567", "480.00"), List.of(invoice), NOW)))
                .as("two characters away is a different reference, not a typo")
                .isEmpty();
    }

    /**
     * B31: money for a cancelled invoice is never confirmed on its own, and never quietly dropped either. It
     * becomes a proposal that says what happened, so a person decides whether to refund or re-issue.
     */
    @Test
    void money_for_a_cancelled_invoice_goes_to_a_person_with_an_explanation() {
        Invoice cancelled = invoice("F-2026-0522", "480.00", SCOR, "Keller GmbH", BOOKED.plusDays(5)).cancel();

        ReconciliationDecision decision = matcher.decide(scorPayment("480.00"), List.of(cancelled), NOW);

        assertThat(decision).isInstanceOf(ReconciliationDecision.NeedsReview.class);
        assertThat(rules(decision)).containsExactly(MatchRule.R2);
        assertThat(explanation(decision)).contains("cancelled").contains("refund the payment or re-issue");
    }

    /** R6 groups invoices of one debtor: neither the number in the text nor a similar name, no combination. */
    @Test
    void R6_ignores_invoices_that_belong_to_nobody_in_the_payment() {
        List<Invoice> strangers = List.of(
                invoice("F-2026-0530", "150.00", PaymentReference.none(), "Pinturas Sol SA", BOOKED.plusDays(5)),
                invoice("F-2026-0531", "150.00", PaymentReference.none(), "Pinturas Sol SA", BOOKED.plusDays(5)));

        assertThat(rules(matcher.decide(namedPayment("300.00", "Keller GmbH"), strangers, NOW))).isEmpty();
    }

    /** B29: the limits that keep R6 from exploding are themselves boundaries. */
    @Test
    void the_smallest_useful_R6_limits_are_accepted_and_anything_smaller_is_refused() {
        assertThat(policy(2, 2, 1)).isNotNull();

        assertThatThrownBy(() -> policy(1, 2, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(2, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> policy(2, 2, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    /** B29: with a budget of one combination, R6 gives up instead of searching a large debtor's invoices. */
    @Test
    void R6_stops_at_its_budget_instead_of_searching_on() {
        List<Invoice> many = List.of(
                invoice("F-2026-0510", "100.00", PaymentReference.none(), "Keller GmbH", BOOKED.plusDays(5)),
                invoice("F-2026-0511", "200.00", PaymentReference.none(), "Keller GmbH", BOOKED.plusDays(5)),
                invoice("F-2026-0512", "300.00", PaymentReference.none(), "Keller GmbH", BOOKED.plusDays(5)),
                invoice("F-2026-0513", "400.00", PaymentReference.none(), "Keller GmbH", BOOKED.plusDays(5)));

        ReconciliationDecision withABudgetOfOne = new Matcher(policy(5, 20, 1))
                .decide(namedPayment("700.00", "Keller GmbH"), many, NOW);
        ReconciliationDecision withTheUsualBudget =
                matcher.decide(namedPayment("700.00", "Keller GmbH"), many, NOW);

        assertThat(rules(withABudgetOfOne)).isEmpty();
        assertThat(rules(withTheUsualBudget)).contains(MatchRule.R6);
    }

    private static MatchingPolicy policy(int maxInCombination, int maxSearched, int budget) {
        return new MatchingPolicy(POLICY.maxChargesRatio(), POLICY.maxChargesAmount(), POLICY.nameThreshold(),
                POLICY.dueDateWindowDays(), maxInCombination, maxSearched, budget, POLICY.ambiguityMargin());
    }

    private static MatchingPolicy policyWithNameThreshold(BigDecimal threshold) {
        return new MatchingPolicy(POLICY.maxChargesRatio(), POLICY.maxChargesAmount(), threshold,
                POLICY.dueDateWindowDays(), POLICY.maxInvoicesInCombination(), POLICY.maxInvoicesSearched(),
                POLICY.combinationBudget(), POLICY.ambiguityMargin());
    }

    private static List<MatchRule> rules(ReconciliationDecision decision) {
        return switch (decision) {
            case ReconciliationDecision.NoMatch ignored -> List.of();
            case ReconciliationDecision.AutoConfirmed confirmed -> List.of(confirmed.allocations().getFirst().rule());
            case ReconciliationDecision.NeedsReview review -> review.proposals().stream()
                    .map(Allocation::rule).distinct().toList();
        };
    }

    private static String explanation(ReconciliationDecision decision) {
        return switch (decision) {
            case ReconciliationDecision.NoMatch ignored -> "";
            case ReconciliationDecision.AutoConfirmed confirmed -> confirmed.allocations().getFirst().explanation();
            case ReconciliationDecision.NeedsReview review -> review.proposals().getFirst().explanation();
        };
    }

    private static List<UUID> combinedInvoices(ReconciliationDecision decision) {
        return switch (decision) {
            case ReconciliationDecision.NeedsReview review -> {
                UUID group = review.proposals().stream()
                        .filter(proposal -> proposal.rule() == MatchRule.R6)
                        .map(Allocation::groupId)
                        .findFirst()
                        .orElseThrow();
                yield review.proposals().stream()
                        .filter(proposal -> proposal.groupId().equals(group))
                        .map(Allocation::invoiceId)
                        .toList();
            }
            default -> List.of();
        };
    }

    private static Invoice invoice(String number, String amount, PaymentReference reference, String debtor,
            LocalDate due) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), ACCOUNT, debtor, Money.chf(amount),
                reference, due);
    }

    private static PaymentToMatch scorPayment(String amount) {
        return PaymentToMatch.credit(UUID.randomUUID(), ACCOUNT, Money.chf(amount), SCOR, BOOKED);
    }

    private static PaymentToMatch typedReference(String reference, String amount) {
        return new PaymentToMatch(UUID.randomUUID(), ACCOUNT, Direction.CREDIT, Money.chf(amount),
                new PaymentReference.FreeText(reference), null, null, null, null, false, BOOKED, 0);
    }

    private static PaymentToMatch namedPayment(String amount, String payer) {
        return new PaymentToMatch(UUID.randomUUID(), ACCOUNT, Direction.CREDIT, Money.chf(amount),
                PaymentReference.none(), null, payer, null, null, false, BOOKED, 0);
    }
}
