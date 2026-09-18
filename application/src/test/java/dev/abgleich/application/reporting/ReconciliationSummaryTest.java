package dev.abgleich.application.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.reporting.ReconciliationSummary.ChannelImports;
import dev.abgleich.application.reporting.ReconciliationSummary.Payments;
import dev.abgleich.application.reporting.ReconciliationSummary.RuleOutcome;
import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.domain.matching.MatchRule;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReconciliationSummaryTest {

    @Test
    void auto_reconciled_share_is_a_percentage_with_one_decimal() {
        assertThat(new Payments(19, 7, 2, 6, 4, 0).autoReconciledPercent()).isEqualByComparingTo("36.8");
        assertThat(new Payments(0, 0, 0, 0, 0, 0).autoReconciledPercent()).isEqualTo(new BigDecimal("0.0"));
    }

    @Test
    void B27_agreement_with_a_rule_is_unknown_until_a_person_decided() {
        assertThat(new RuleOutcome(MatchRule.R4, 0, 0, 0, 3).reviewerAgreementPercent()).isEmpty();
        assertThat(new RuleOutcome(MatchRule.R5, 0, 2, 1, 0).reviewerAgreementPercent())
                .hasValueSatisfying(percent -> assertThat(percent).isEqualByComparingTo("66.7"));
    }

    /**
     * A count that went negative means a query is wrong, and a summary is read by people deciding what to work on:
     * failing here is better than showing "-3 payments waiting".
     */
    @Test
    void no_count_of_payments_can_be_negative() {
        assertThatThrownBy(() -> new Payments(-1, 0, 0, 0, 0, 0)).hasMessageContaining("credits");
        assertThatThrownBy(() -> new Payments(0, -1, 0, 0, 0, 0)).hasMessageContaining("autoConfirmed");
        assertThatThrownBy(() -> new Payments(0, 0, -1, 0, 0, 0)).hasMessageContaining("confirmedByReview");
        assertThatThrownBy(() -> new Payments(0, 0, 0, -1, 0, 0)).hasMessageContaining("waitingForReview");
        assertThatThrownBy(() -> new Payments(0, 0, 0, 0, -1, 0)).hasMessageContaining("unmatched");
        assertThatThrownBy(() -> new Payments(0, 0, 0, 0, 0, -1)).hasMessageContaining("reversed");
    }

    @Test
    void no_count_of_a_rule_can_be_negative_either() {
        assertThatThrownBy(() -> new RuleOutcome(MatchRule.R1, -1, 0, 0, 0)).hasMessageContaining("autoConfirmed");
        assertThatThrownBy(() -> new RuleOutcome(MatchRule.R1, 0, -1, 0, 0)).hasMessageContaining("confirmed");
        assertThatThrownBy(() -> new RuleOutcome(MatchRule.R1, 0, 0, -1, 0)).hasMessageContaining("rejected");
        assertThatThrownBy(() -> new RuleOutcome(MatchRule.R1, 0, 0, 0, -1)).hasMessageContaining("pending");
    }

    @Test
    void neither_can_the_pending_outbox_nor_a_channel_count() {
        List<RuleOutcome> rules = Arrays.stream(MatchRule.values()).map(rule -> new RuleOutcome(rule, 0, 0, 0, 0)).toList();

        assertThatThrownBy(() -> new ReconciliationSummary(new Payments(0, 0, 0, 0, 0, 0), List.of(), List.of(),
                rules, List.of(), -1)).hasMessageContaining("outboxPending");
        assertThatThrownBy(() -> new ChannelImports(ImportSource.WEB, -1)).hasMessageContaining("files");
    }

    @Test
    void agreement_is_all_or_nothing_when_everyone_agreed_or_nobody_did() {
        assertThat(new RuleOutcome(MatchRule.R2, 0, 4, 0, 1).reviewerAgreementPercent())
                .hasValueSatisfying(percent -> assertThat(percent).isEqualByComparingTo("100.0"));
        assertThat(new RuleOutcome(MatchRule.R3, 0, 0, 4, 1).reviewerAgreementPercent())
                .hasValueSatisfying(percent -> assertThat(percent).isEqualByComparingTo("0.0"));
    }

    /** Auto-confirmations are not decisions a person made, so they never count as agreement. */
    @Test
    void automatic_confirmations_do_not_count_as_people_agreeing() {
        assertThat(new RuleOutcome(MatchRule.R1, 80, 0, 0, 0).reviewerAgreementPercent()).isEmpty();
    }

    @Test
    void every_rule_is_listed_once_in_order() {
        List<RuleOutcome> rules = Arrays.stream(MatchRule.values()).map(rule -> new RuleOutcome(rule, 0, 0, 0, 0)).toList();

        assertThatThrownBy(() -> new ReconciliationSummary(new Payments(0, 0, 0, 0, 0, 0), List.of(), List.of(),
                rules.reversed(), List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReconciliationSummary(new Payments(0, 0, 0, 0, 0, 0), List.of(), List.of(), rules, List.of(), 0)
                .rules()).hasSize(6);
    }
}
