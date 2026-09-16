package dev.abgleich.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.matching.ReconciliationDecision.AutoConfirmed;
import dev.abgleich.domain.matching.ReconciliationDecision.NeedsReview;
import dev.abgleich.domain.matching.ReconciliationDecision.NoMatch;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.reference.QrReference;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");
    private static final LocalDate BOOKED = LocalDate.of(2026, 9, 15);
    private static final LocalDate DUE = LocalDate.of(2026, 9, 30);
    private static final Iban QR_IBAN = Iban.of("CH4431999123000889012");
    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final PaymentReference QRR = new PaymentReference.Qrr(QrReference.of("210000000003139471430009017"));
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));

    private final Matcher matcher = new Matcher();

    @Nested
    class R1 {

        @Test
        void exact_qr_reference_and_amount_is_confirmed_automatically() {
            Invoice invoice = invoice("F-2026-0141", QR_IBAN, "1250.00", QRR);
            PaymentToMatch payment = PaymentToMatch.credit(UUID.randomUUID(), QR_IBAN, Money.chf("1250.00"), QRR, BOOKED);

            AutoConfirmed decision = (AutoConfirmed) matcher.decide(payment, List.of(invoice), NOW);

            assertThat(decision.allocations()).singleElement().satisfies(allocation -> {
                assertThat(allocation.rule()).isEqualTo(MatchRule.R1);
                assertThat(allocation.confidence()).isEqualTo(Confidence.CERTAIN);
                assertThat(allocation.invoiceId()).isEqualTo(invoice.id());
                assertThat(allocation.amount()).isEqualTo(Money.chf("1250.00"));
                assertThat(allocation.status()).isEqualTo(AllocationStatus.CONFIRMED);
                assertThat(allocation.decidedBy()).isEqualTo(Allocation.SYSTEM);
                assertThat(allocation.explanation()).isEqualTo("QR reference 21 00000 00003 13947 14300 09017"
                        + " and amount CHF 1250.00 match invoice F-2026-0141 exactly");
            });
        }

        @Test
        void B38_decision_time_comes_from_the_caller_clock() {
            AutoConfirmed decision = (AutoConfirmed) matcher.decide(scorPayment("480.00"),
                    List.of(invoice("F-2026-0142", ACCOUNT, "480.00", SCOR)), NOW);

            assertThat(decision.allocations().getFirst().decidedAt()).isEqualTo(NOW);
        }

        @Test
        void partially_paid_invoice_matches_its_outstanding_amount() {
            Invoice partial = invoice("F-2026-0144", ACCOUNT, "2000.00", SCOR).withConfirmedPayment(Money.chf("1200.00"));

            assertThat(matcher.decide(scorPayment("800.00"), List.of(partial), NOW)).isInstanceOf(AutoConfirmed.class);
        }

        @Test
        void a_weaker_rule_for_the_same_invoice_does_not_block_R1() {
            Invoice invoice = invoice("F-2026-0142", ACCOUNT, "480.00", SCOR, "Keller GmbH", DUE);
            PaymentToMatch payment = payment("480.00", SCOR, "Rechnung F-2026-0142", "Keller GmbH");

            assertThat(matcher.decide(payment, List.of(invoice), NOW)).isInstanceOf(AutoConfirmed.class);
        }
    }

    @Nested
    class R2 {

        @Test
        void B07_payment_a_few_francs_short_is_proposed_as_settled_with_charges() {
            Invoice invoice = invoice("F-2026-0142", ACCOUNT, "480.00", SCOR);

            NeedsReview review = (NeedsReview) matcher.decide(scorPayment("472.50"), List.of(invoice), NOW);

            assertThat(review.proposals()).singleElement().satisfies(proposal -> {
                assertThat(proposal.rule()).isEqualTo(MatchRule.R2);
                assertThat(proposal.amount()).isEqualTo(Money.chf("472.50"));
                assertThat(proposal.chargesWrittenOff()).isEqualTo(Money.chf("7.50"));
                assertThat(proposal.settledAmount()).isEqualTo(Money.chf("480.00"));
                assertThat(proposal.explanation()).contains("looks like bank charges").doesNotContain("partial");
            });
        }

        @Test
        void B07_charges_reported_by_the_bank_explain_a_larger_difference() {
            Invoice invoice = invoice("F-2026-0142", ACCOUNT, "2000.00", SCOR);
            PaymentToMatch payment = new PaymentToMatch(UUID.randomUUID(), ACCOUNT, Direction.CREDIT,
                    Money.chf("1955.00"), SCOR, null, null, null, Money.chf("45.00"), false, BOOKED, 0);

            NeedsReview review = (NeedsReview) matcher.decide(payment, List.of(invoice), NOW);

            assertThat(review.proposals().getFirst().chargesWrittenOff()).isEqualTo(Money.chf("45.00"));
        }

        @Test
        void B07_a_large_difference_is_a_partial_payment_not_charges() {
            NeedsReview review = (NeedsReview) matcher.decide(scorPayment("200.00"),
                    List.of(invoice("F-2026-0142", ACCOUNT, "480.00", SCOR)), NOW);

            assertThat(review.proposals()).singleElement().satisfies(proposal -> {
                assertThat(proposal.chargesWrittenOff()).isEqualTo(Money.chf("0.00"));
                assertThat(proposal.explanation()).contains("partial payment, CHF 280.00 would stay open");
            });
        }

        @Test
        void overpayment_is_proposed_with_a_warning() {
            NeedsReview review = (NeedsReview) matcher.decide(scorPayment("500.00"),
                    List.of(invoice("F-2026-0142", ACCOUNT, "480.00", SCOR)), NOW);

            assertThat(review.proposals().getFirst().explanation()).contains("CHF 20.00 more than outstanding");
        }

        @Test
        void B31_payment_to_cancelled_goes_to_review() {
            Invoice cancelled = invoice("F-2026-0142", ACCOUNT, "480.00", SCOR).cancel();

            NeedsReview review = (NeedsReview) matcher.decide(scorPayment("480.00"), List.of(cancelled), NOW);

            assertThat(review.proposals().getFirst().explanation()).contains("which is cancelled");
        }

        @Test
        void B31_second_payment_to_a_paid_invoice_goes_to_review_as_possible_duplicate() {
            Invoice paid = invoice("F-2026-0142", ACCOUNT, "480.00", SCOR).withConfirmedPayment(Money.chf("480.00"));

            NeedsReview review = (NeedsReview) matcher.decide(scorPayment("480.00"), List.of(paid), NOW);

            assertThat(review.proposals().getFirst().explanation()).contains("already paid: possible duplicate payment");
        }
    }

    @Nested
    class R3_R4_R5 {

        @Test
        void R3_reference_with_one_wrong_character_and_exact_amount() {
            Invoice invoice = invoice("F-2026-0142", ACCOUNT, "480.00", SCOR);

            NeedsReview review = (NeedsReview) matcher.decide(
                    payment("480.00", PaymentReference.parse("RF18539007547035"), null, null), List.of(invoice), NOW);

            assertThat(review.proposals()).singleElement().extracting(Allocation::rule).isEqualTo(MatchRule.R3);
        }

        @Test
        void R4_invoice_number_in_the_remittance_text_and_exact_amount() {
            Invoice invoice = invoice("FV-2026-0087", ACCOUNT, "1815.00", PaymentReference.none());

            NeedsReview review = (NeedsReview) matcher.decide(
                    payment("1815.00", PaymentReference.none(), "TRANSF TALLERES RUIZ SL FRA 87", null), List.of(invoice), NOW);

            assertThat(review.proposals()).singleElement().satisfies(proposal -> {
                assertThat(proposal.rule()).isEqualTo(MatchRule.R4);
                assertThat(proposal.confidence()).isEqualTo(Confidence.of("0.80"));
            });
        }

        @Test
        void R4_needs_the_exact_amount() {
            Invoice invoice = invoice("FV-2026-0087", ACCOUNT, "1815.00", PaymentReference.none());

            assertThat(matcher.decide(payment("1800.00", PaymentReference.none(), "FRA 87", null), List.of(invoice), NOW))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void R5_similar_payer_name_near_the_due_date() {
            Invoice invoice = invoice("F-2026-0144", ACCOUNT, "2000.00", PaymentReference.none(), "Brunner & Co. AG", DUE);

            NeedsReview review = (NeedsReview) matcher.decide(
                    payment("1200.00", PaymentReference.none(), null, "Brunner und Co"), List.of(invoice), NOW);

            assertThat(review.proposals()).singleElement().satisfies(proposal -> {
                assertThat(proposal.rule()).isEqualTo(MatchRule.R5);
                assertThat(proposal.explanation()).contains("partial payment, CHF 800.00 would stay open")
                        .contains("booked 15 days from the due date");
            });
        }

        @Test
        void R5_ignores_invoices_due_long_before_or_after_the_payment() {
            Invoice old = invoice("F-2026-0001", ACCOUNT, "2000.00", PaymentReference.none(), "Brunner & Co. AG",
                    BOOKED.minusDays(46));

            assertThat(matcher.decide(payment("2000.00", PaymentReference.none(), null, "Brunner & Co. AG"), List.of(old), NOW))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void R5_finds_the_debtor_name_inside_a_norma43_concept_with_the_exact_amount() {
            Invoice invoice = invoice("FV-2026-0104", ACCOUNT, "879.25", PaymentReference.none(), "JOSÉ MUÑOZ ÁLVAREZ", DUE);

            NeedsReview review = (NeedsReview) matcher.decide(
                    payment("879.25", PaymentReference.none(), "TRANSF JOSE MUNOZ ALVAREZ", null), List.of(invoice), NOW);

            assertThat(review.proposals()).singleElement().satisfies(proposal -> {
                assertThat(proposal.rule()).isEqualTo(MatchRule.R5);
                assertThat(proposal.explanation()).contains("appears in the remittance text");
            });
        }

        @Test
        void R5_a_name_in_the_text_never_proposes_a_partial_payment() {
            Invoice invoice = invoice("FV-2026-0103", ACCOUNT, "1083.15", PaymentReference.none(), "PINTURAS SOL SA", DUE);

            assertThat(matcher.decide(payment("605.00", PaymentReference.none(),
                    "CUOTA SERVICIO MANTENIMIENTO PINTURAS SOL SA", null), List.of(invoice), NOW))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void R5_a_name_in_the_text_must_be_the_whole_name() {
            Invoice invoice = invoice("FV-2026-0101", ACCOUNT, "605.00", PaymentReference.none(), "PINTURAS SOL SA", DUE);

            assertThat(matcher.decide(payment("605.00", PaymentReference.none(), "TRANSF PINTURAS LUNA SA", null),
                    List.of(invoice), NOW)).isInstanceOf(NoMatch.class);
        }

        @Test
        void R5_needs_a_similar_name_not_just_a_shared_word() {
            Invoice invoice = invoice("FV-2026-0101", ACCOUNT, "605.00", PaymentReference.none(), "PINTURAS SOL SA", DUE);

            assertThat(matcher.decide(payment("605.00", PaymentReference.none(), null, "PINTURAS LUNA SA"), List.of(invoice), NOW))
                    .isInstanceOf(NoMatch.class);
        }
    }

    @Nested
    class R6 {

        @Test
        void one_payment_for_invoices_named_in_the_text() {
            Invoice first = invoice("FV-2026-0091", ACCOUNT, "250.00", PaymentReference.none(), "PINTURAS SOL SA", DUE);
            Invoice second = invoice("FV-2026-0092", ACCOUNT, "355.00", PaymentReference.none(), "PINTURAS SOL SA", DUE);
            Invoice other = invoice("FV-2026-0093", ACCOUNT, "605.00", PaymentReference.none(), "HOTEL MIRAMAR SA", DUE);

            NeedsReview review = (NeedsReview) matcher.decide(
                    payment("605.00", PaymentReference.none(), "PAGO FACTURAS 91 Y 92", "PINTURAS SOL SA"),
                    List.of(first, second, other), NOW);

            assertThat(review.groups()).isEqualTo(1);
            assertThat(review.proposals()).extracting(Allocation::invoiceId).containsExactlyInAnyOrder(first.id(), second.id());
            assertThat(review.proposals()).extracting(Allocation::amount)
                    .containsExactlyInAnyOrder(Money.chf("250.00"), Money.chf("355.00"));
            assertThat(review.proposals()).extracting(Allocation::rule).containsOnly(MatchRule.R6);
        }

        @Test
        void B29_subset_search_is_bounded() {
            // 40 invoices of one debtor whose amounts can never add up to the payment: every subset is a dead end.
            List<Invoice> invoices = IntStream.range(0, 40)
                    .mapToObj(i -> invoice("F-" + (1000 + i), ACCOUNT, (2 * i + 2) + ".00", PaymentReference.none(),
                            "Keller Elektro AG", DUE))
                    .toList();
            SubsetSearch search = new SubsetSearch(5, 20_000, 3);

            List<List<Invoice>> found = search.find(invoices, Money.chf("999.99"), 20);

            assertThat(found).isEmpty();
            assertThat(search.visited()).isLessThanOrEqualTo(20_000);
            assertThat(matcher.decide(payment("999.99", PaymentReference.none(), null, "Keller Elektro AG"), invoices, NOW))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void B29_budget_stops_the_search_even_with_a_generous_policy() {
            List<Invoice> invoices = IntStream.range(0, 30)
                    .mapToObj(i -> invoice("F-" + (1000 + i), ACCOUNT, "1.00", PaymentReference.none(), "Keller", DUE))
                    .toList();
            SubsetSearch search = new SubsetSearch(30, 500, Integer.MAX_VALUE);

            search.find(invoices, Money.chf("30.01"), 30);

            assertThat(search.exhausted()).isTrue();
            assertThat(search.visited()).isEqualTo(500);
        }
    }

    @Nested
    class Ranking {

        @Test
        void B10_debits_never_pay_invoices() {
            PaymentToMatch refund = new PaymentToMatch(UUID.randomUUID(), ACCOUNT, Direction.DEBIT, Money.chf("480.00"),
                    SCOR, null, null, null, null, false, BOOKED, 0);

            assertThat(matcher.decide(refund, List.of(invoice("F-2026-0142", ACCOUNT, "480.00", SCOR)), NOW))
                    .isEqualTo(new NoMatch("Debits never pay invoices"));
        }

        @Test
        void B06_same_number_in_another_currency_never_matches() {
            PaymentToMatch euros = PaymentToMatch.credit(UUID.randomUUID(), ACCOUNT, Money.eur("480.00"), SCOR, BOOKED);

            assertThat(matcher.decide(euros, List.of(invoice("F-2026-0142", ACCOUNT, "480.00", SCOR)), NOW))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void invoice_of_another_account_never_matches() {
            PaymentToMatch otherAccount = PaymentToMatch.credit(UUID.randomUUID(), Iban.of("CH5604835012345678009"),
                    Money.chf("480.00"), SCOR, BOOKED);

            assertThat(matcher.decide(otherAccount, List.of(invoice("F-2026-0142", ACCOUNT, "480.00", SCOR)), NOW))
                    .isInstanceOf(NoMatch.class);
        }

        @Test
        void B28_ties_go_to_review() {
            List<Invoice> twins = List.of(invoice("F-2026-0200", ACCOUNT, "480.00", SCOR),
                    invoice("F-2026-0199", ACCOUNT, "480.00", SCOR));

            NeedsReview review = (NeedsReview) matcher.decide(scorPayment("480.00"), twins, NOW);

            assertThat(review.groups()).isEqualTo(2);
            assertThat(review.proposals()).allSatisfy(p -> assertThat(p.explanation())
                    .endsWith("(2 candidates are about equally likely: choose one)"));
        }

        @Test
        void B28_a_runner_up_exactly_at_the_margin_is_not_a_tie() {
            List<Invoice> invoices = kellerInvoices();

            NeedsReview review = (NeedsReview) matcher.decide(
                    payment("300.00", PaymentReference.none(), null, "Keller Elektro AG"), invoices, NOW);

            assertThat(review.groups()).as("R6 (0.75) and R5 (0.70) differ by exactly 0.05, not less").isEqualTo(1);
            assertThat(review.proposals()).extracting(Allocation::rule).containsOnly(MatchRule.R6);
        }

        @Test
        void B28_close_candidates_from_different_rules_are_a_tie_with_a_wider_margin() {
            MatchingPolicy wider = new MatchingPolicy(new BigDecimal("0.02"), new BigDecimal("30.00"),
                    new BigDecimal("0.90"), 45, 5, 20, 20_000, new BigDecimal("0.10"));

            NeedsReview review = (NeedsReview) new Matcher(wider).decide(
                    payment("300.00", PaymentReference.none(), null, "Keller Elektro AG"), kellerInvoices(), NOW);

            assertThat(review.groups()).isEqualTo(2);
            assertThat(review.proposals().getFirst().rule()).as("best first").isEqualTo(MatchRule.R6);
        }

        private List<Invoice> kellerInvoices() {
            return List.of(
                    invoice("F-2026-0300", ACCOUNT, "300.00", PaymentReference.none(), "Keller Elektro AG", DUE),
                    invoice("F-2026-0301", ACCOUNT, "100.00", PaymentReference.none(), "Keller Elektro AG", DUE),
                    invoice("F-2026-0302", ACCOUNT, "200.00", PaymentReference.none(), "Keller Elektro AG", DUE));
        }

        @Property
        void B28_proposals_keep_the_same_order_whatever_the_order_of_invoices(@ForAll @IntRange(max = 5) int rotation) {
            List<Invoice> invoices = new ArrayList<>(List.of(
                    invoice("F-3", ACCOUNT, "480.00", SCOR, "A", DUE.plusDays(1)),
                    invoice("F-1", ACCOUNT, "480.00", SCOR, "A", DUE.plusDays(1)),
                    invoice("F-2", ACCOUNT, "480.00", SCOR, "A", DUE)));
            Collections.rotate(invoices, rotation);
            List<UUID> expected = invoices.stream()
                    .sorted(Comparator.comparing(Invoice::dueDate).thenComparing(i -> i.number().value()))
                    .map(Invoice::id).toList();

            NeedsReview review = (NeedsReview) matcher.decide(scorPayment("480.00"), invoices, NOW);

            assertThat(review.proposals()).extracting(Allocation::invoiceId).containsExactlyElementsOf(expected);
        }

        @Test
        void B27_only_r1_auto_confirms() {
            for (MatchRule rule : MatchRule.values()) {
                Allocation single = Allocation.propose(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        Money.chf("480.00"), null, rule, "Candidate", NOW);

                assertThat(AutoConfirmPolicy.decide(rule, List.of(single), NOW))
                        .as("rule %s with a single candidate", rule)
                        .isInstanceOf(rule == MatchRule.R1 ? AutoConfirmed.class : NeedsReview.class);
            }
        }

        @Test
        void a_rejected_proposal_is_never_proposed_again_and_the_next_candidate_gets_its_chance() {
            Invoice rejectedInvoice = invoice("F-2026-0199", ACCOUNT, "480.00", SCOR);
            Invoice other = invoice("F-2026-0200", ACCOUNT, "480.00", SCOR);

            ReconciliationDecision decision = matcher.decide(scorPayment("480.00"), List.of(rejectedInvoice, other),
                    java.util.Set.of(java.util.Set.of(rejectedInvoice.id())), NOW);

            assertThat(decision).as("the remaining exact match is no longer a tie").isInstanceOf(AutoConfirmed.class);
            assertThat(((AutoConfirmed) decision).allocations().getFirst().invoiceId()).isEqualTo(other.id());
        }

        @Test
        void no_rule_means_no_match() {
            assertThat(matcher.decide(payment("99.00", PaymentReference.none(), "Spende", "Unbekannt"),
                    List.of(invoice("F-2026-0142", ACCOUNT, "480.00", SCOR)), NOW)).isInstanceOf(NoMatch.class);
        }
    }

    private static Invoice invoice(String number, Iban creditor, String amount, PaymentReference reference) {
        return invoice(number, creditor, amount, reference, "Keller GmbH", DUE);
    }

    private static Invoice invoice(String number, Iban creditor, String amount, PaymentReference reference,
            String debtor, LocalDate due) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), creditor, debtor, Money.chf(amount),
                reference, due);
    }

    private static PaymentToMatch scorPayment(String amount) {
        return PaymentToMatch.credit(UUID.randomUUID(), ACCOUNT, Money.chf(amount), SCOR, BOOKED);
    }

    private static PaymentToMatch payment(String amount, PaymentReference reference, String text, String payer) {
        return new PaymentToMatch(UUID.randomUUID(), ACCOUNT, Direction.CREDIT, Money.chf(amount), reference, text,
                payer, null, null, false, BOOKED, 0);
    }
}
