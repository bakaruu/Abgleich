package dev.abgleich.application.port.in;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.port.in.ReconciliationSummary.Payments;
import dev.abgleich.application.port.in.ReconciliationSummary.RuleOutcome;
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

    @Test
    void every_rule_is_listed_once_in_order() {
        List<RuleOutcome> rules = Arrays.stream(MatchRule.values()).map(rule -> new RuleOutcome(rule, 0, 0, 0, 0)).toList();

        assertThatThrownBy(() -> new ReconciliationSummary(new Payments(0, 0, 0, 0, 0, 0), List.of(), List.of(),
                rules.reversed(), List.of(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new ReconciliationSummary(new Payments(0, 0, 0, 0, 0, 0), List.of(), List.of(), rules, List.of(), 0)
                .rules()).hasSize(6);
    }
}
