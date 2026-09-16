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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class MatcherTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");
    private static final Iban QR_IBAN = Iban.of("CH4431999123000889012");
    private static final Iban REGULAR_IBAN = Iban.of("CH9300762011623852957");
    private static final PaymentReference QRR = new PaymentReference.Qrr(QrReference.of("210000000003139471430009017"));
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));

    private final Matcher matcher = new Matcher();

    @Test
    void R1_exact_qr_reference_and_amount_is_confirmed_automatically() {
        Invoice invoice = invoice("F-2026-0141", QR_IBAN, "1250.00", QRR);
        PaymentToMatch payment = credit(QR_IBAN, "1250.00", QRR);

        ReconciliationDecision decision = matcher.decide(payment, List.of(invoice), NOW);

        assertThat(decision).isInstanceOfSatisfying(AutoConfirmed.class, confirmed -> {
            Allocation allocation = confirmed.allocation();
            assertThat(allocation.rule()).isEqualTo(MatchRule.R1);
            assertThat(allocation.confidence()).isEqualTo(Confidence.CERTAIN);
            assertThat(allocation.invoiceId()).isEqualTo(invoice.id());
            assertThat(allocation.transactionId()).isEqualTo(payment.transactionId());
            assertThat(allocation.amount()).isEqualTo(Money.chf("1250.00"));
            assertThat(allocation.status()).isEqualTo(AllocationStatus.CONFIRMED);
            assertThat(allocation.decidedBy()).isEqualTo(Allocation.SYSTEM);
            assertThat(allocation.explanation()).isEqualTo("QR reference 21 00000 00003 13947 14300 09017"
                    + " and amount CHF 1250.00 match invoice F-2026-0141 exactly");
        });
    }

    @Test
    void B38_decision_time_comes_from_the_caller_clock() {
        AutoConfirmed decision = (AutoConfirmed) matcher.decide(credit(REGULAR_IBAN, "480.00", SCOR),
                List.of(invoice("F-2026-0142", REGULAR_IBAN, "480.00", SCOR)), NOW);

        assertThat(decision.allocation().decidedAt()).isEqualTo(NOW);
        assertThat(decision.allocation().createdAt()).isEqualTo(NOW);
    }

    @Test
    void R1_partially_paid_invoice_matches_its_outstanding_amount() {
        Invoice partial = invoice("F-2026-0144", REGULAR_IBAN, "2000.00", SCOR).withConfirmedPayment(Money.chf("1200.00"));

        assertThat(matcher.decide(credit(REGULAR_IBAN, "800.00", SCOR), List.of(partial), NOW))
                .isInstanceOf(AutoConfirmed.class);
    }

    @Test
    void B10_debits_never_pay_invoices() {
        PaymentToMatch refund = new PaymentToMatch(UUID.randomUUID(), REGULAR_IBAN, Direction.DEBIT,
                Money.chf("480.00"), SCOR, LocalDate.of(2026, 9, 15), 0);

        assertThat(matcher.decide(refund, List.of(invoice("F-2026-0142", REGULAR_IBAN, "480.00", SCOR)), NOW))
                .isEqualTo(new NoMatch("Debits never pay invoices"));
    }

    @Test
    void different_amount_is_not_R1() {
        assertThat(matcher.decide(credit(REGULAR_IBAN, "479.95", SCOR),
                List.of(invoice("F-2026-0142", REGULAR_IBAN, "480.00", SCOR)), NOW))
                .isInstanceOf(NoMatch.class);
    }

    @Test
    void B06_same_number_in_another_currency_is_not_R1() {
        PaymentToMatch euros = new PaymentToMatch(UUID.randomUUID(), REGULAR_IBAN, Direction.CREDIT,
                Money.eur("480.00"), SCOR, LocalDate.of(2026, 9, 15), 0);

        assertThat(matcher.decide(euros, List.of(invoice("F-2026-0142", REGULAR_IBAN, "480.00", SCOR)), NOW))
                .isInstanceOf(NoMatch.class);
    }

    @Test
    void invoice_of_another_account_is_not_R1() {
        Iban otherAccount = Iban.of("CH5604835012345678009");

        assertThat(matcher.decide(credit(otherAccount, "480.00", SCOR),
                List.of(invoice("F-2026-0142", REGULAR_IBAN, "480.00", SCOR)), NOW))
                .isInstanceOf(NoMatch.class);
    }

    @Test
    void paid_invoice_is_not_matched_again() {
        Invoice paid = invoice("F-2026-0142", REGULAR_IBAN, "480.00", SCOR).withConfirmedPayment(Money.chf("480.00"));

        assertThat(matcher.decide(credit(REGULAR_IBAN, "480.00", SCOR), List.of(paid), NOW))
                .isInstanceOf(NoMatch.class);
    }

    @Test
    void payment_without_structured_reference_is_left_to_later_rules() {
        assertThat(matcher.decide(credit(REGULAR_IBAN, "480.00", new PaymentReference.FreeText("Rechnung 143")),
                List.of(invoice("F-2026-0143", REGULAR_IBAN, "480.00", PaymentReference.none())), NOW))
                .isEqualTo(new NoMatch("The payment has no QR or creditor reference"));
    }

    @Test
    void B28_tie_goes_to_review_with_every_candidate() {
        List<Invoice> twins = List.of(
                invoice("F-2026-0200", REGULAR_IBAN, "480.00", SCOR),
                invoice("F-2026-0199", REGULAR_IBAN, "480.00", SCOR));

        ReconciliationDecision decision = matcher.decide(credit(REGULAR_IBAN, "480.00", SCOR), twins, NOW);

        assertThat(decision).isInstanceOfSatisfying(NeedsReview.class, review -> {
            assertThat(review.proposals()).extracting(Allocation::status).containsOnly(AllocationStatus.PROPOSED);
            assertThat(review.proposals()).allSatisfy(p -> assertThat(p.explanation())
                    .endsWith("but 2 open invoices match equally: choose one"));
        });
    }

    @Property
    void B28_proposals_have_the_same_order_whatever_the_order_of_invoices(@ForAll @IntRange(max = 5) int rotation) {
        List<Invoice> invoices = new java.util.ArrayList<>(List.of(
                invoice("F-3", REGULAR_IBAN, "480.00", SCOR),
                invoice("F-1", REGULAR_IBAN, "480.00", SCOR),
                invoice("F-2", REGULAR_IBAN, "480.00", SCOR)));
        java.util.Collections.rotate(invoices, rotation);
        List<UUID> byNumber = invoices.stream()
                .sorted(java.util.Comparator.comparing(i -> i.number().value())).map(Invoice::id).toList();

        NeedsReview review = (NeedsReview) matcher.decide(credit(REGULAR_IBAN, "480.00", SCOR), invoices, NOW);

        assertThat(review.proposals()).extracting(Allocation::invoiceId).containsExactlyElementsOf(byNumber);
    }

    @Test
    void B27_only_r1_auto_confirms() {
        Allocation single = Allocation.propose(UUID.randomUUID(), UUID.randomUUID(), Money.chf("480.00"),
                MatchRule.R1, Confidence.of("0.95"), "Invoice number found in the remittance text", NOW);

        for (MatchRule rule : MatchRule.values()) {
            ReconciliationDecision decision = AutoConfirmPolicy.decide(rule, List.of(single), NOW);
            assertThat(decision)
                    .as("rule %s with a single candidate", rule)
                    .isInstanceOf(rule == MatchRule.R1 ? AutoConfirmed.class : NeedsReview.class);
        }
    }

    private static Invoice invoice(String number, Iban creditor, String amount, PaymentReference reference) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), creditor, "Keller GmbH",
                Money.chf(amount), reference, LocalDate.of(2026, 9, 30));
    }

    private static PaymentToMatch credit(Iban account, String amount, PaymentReference reference) {
        return new PaymentToMatch(UUID.randomUUID(), account, Direction.CREDIT, Money.chf(amount), reference,
                LocalDate.of(2026, 9, 15), 0);
    }
}
