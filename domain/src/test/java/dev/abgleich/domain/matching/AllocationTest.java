package dev.abgleich.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.money.Money;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationTest {

    private static final Instant CREATED = Instant.parse("2026-09-16T06:00:00Z");
    private static final Instant DECIDED = Instant.parse("2026-09-16T09:30:00Z");

    @Test
    void proposal_takes_the_confidence_of_its_rule() {
        assertThat(proposal(MatchRule.R4).confidence()).isEqualTo(Confidence.of("0.80"));
        assertThat(proposal(MatchRule.R4).status()).isEqualTo(AllocationStatus.PROPOSED);
    }

    @Test
    void B33_a_proposal_is_confirmed_once() {
        Allocation confirmed = proposal(MatchRule.R4).confirm("reviewer", DECIDED);

        assertThat(confirmed.status()).isEqualTo(AllocationStatus.CONFIRMED);
        assertThat(confirmed.decidedBy()).isEqualTo("reviewer");
        assertThatThrownBy(() -> confirmed.confirm("reviewer", DECIDED)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> confirmed.reject("reviewer", DECIDED, "no")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void B34_a_rejected_proposal_cannot_be_confirmed_later() {
        Allocation rejected = proposal(MatchRule.R5).reject("first reviewer", DECIDED, "Different customer");

        assertThat(rejected.decisionNote()).isEqualTo("Different customer");
        assertThatThrownBy(() -> rejected.confirm("second reviewer", DECIDED)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void B10_only_a_confirmed_allocation_can_be_reversed_and_keeps_a_trace() {
        Allocation reversed = proposal(MatchRule.R1).confirm(Allocation.SYSTEM, DECIDED)
                .reverse(Allocation.SYSTEM, DECIDED.plusSeconds(60), "Reversed by the bank");

        assertThat(reversed.status()).isEqualTo(AllocationStatus.REVERSED);
        assertThat(reversed.decisionNote()).isEqualTo("Reversed by the bank");
        assertThatThrownBy(() -> proposal(MatchRule.R1).reverse("x", DECIDED, "note"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void B07_settled_amount_includes_accepted_charges() {
        Allocation withCharges = Allocation.propose(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Money.chf("472.50"), Money.chf("7.50"), MatchRule.R2, "Short by charges", CREATED);

        assertThat(withCharges.settledAmount()).isEqualTo(Money.chf("480.00"));
    }

    private static Allocation proposal(MatchRule rule) {
        return Allocation.propose(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Money.chf("480.00"), null,
                rule, "Candidate", CREATED);
    }
}
