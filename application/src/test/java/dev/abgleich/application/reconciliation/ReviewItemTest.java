package dev.abgleich.application.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.reconciliation.ReviewItem.Proposal;
import dev.abgleich.application.reconciliation.ReviewItem.Share;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.matching.Confidence;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** What the review screen is given. A proposal for several invoices is shown differently, so it must say so. */
class ReviewItemTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    @Test
    void a_proposal_knows_whether_it_settles_one_invoice_or_several() {
        assertThat(proposal(MatchRule.R1, 1).severalInvoices()).isFalse();
        assertThat(proposal(MatchRule.R6, 2).severalInvoices()).isTrue();
        assertThat(proposal(MatchRule.R6, 5).severalInvoices()).isTrue();
    }

    @Test
    void the_lists_it_is_given_cannot_change_underneath_it() {
        List<Proposal> proposals = new ArrayList<>(List.of(proposal(MatchRule.R1, 1)));
        ReviewItem item = item(proposals);

        proposals.clear();

        assertThat(item.proposals()).hasSize(1);
        assertThatThrownBy(() -> item.proposals().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void a_payment_always_names_its_transaction_account_and_amount() {
        assertThatThrownBy(() -> new ReviewItem(null, 0, ACCOUNT, DAY, Money.chf("10.00"), null, null, null, List.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("transactionId");
        assertThatThrownBy(() -> new ReviewItem(UUID.randomUUID(), 0, null, DAY, Money.chf("10.00"), null, null, null,
                List.of())).isInstanceOf(NullPointerException.class).hasMessageContaining("account");
        assertThatThrownBy(() -> new ReviewItem(UUID.randomUUID(), 0, ACCOUNT, DAY, null, null, null, null, List.of()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("amount");
    }

    @Test
    void a_proposal_always_belongs_to_a_group() {
        assertThatThrownBy(() -> new Proposal(null, MatchRule.R1, MatchRule.R1.confidence(), "why", List.of(share())))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("groupId");
    }

    private static ReviewItem item(List<Proposal> proposals) {
        return new ReviewItem(UUID.randomUUID(), 3, ACCOUNT, DAY, Money.chf("480.00"), "Keller GmbH",
                "Rechnung 142", null, proposals);
    }

    private static Proposal proposal(MatchRule rule, int invoices) {
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < invoices; i++) {
            shares.add(share());
        }
        Confidence confidence = rule.confidence();
        return new Proposal(UUID.randomUUID(), rule, confidence, "because", shares);
    }

    private static Share share() {
        return new Share(UUID.randomUUID(), "F-2026-0142", "Keller GmbH", Money.chf("480.00"), Money.chf("480.00"),
                Money.chf("480.00"), Money.chf("0.00"), InvoiceStatus.OPEN, DAY);
    }
}
