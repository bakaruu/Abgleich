package dev.abgleich.adapter.out.synthetic.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.adapter.out.synthetic.evaluation.MatchingDataset.Kind;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.matching.Matcher;
import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

/**
 * The matching evaluation as a build gate. The dataset is written by the same people as the rules, so
 * these numbers are an upper bound, not a promise for real bank data; the one guarantee that must hold
 * everywhere is that no automatic confirmation is wrong.
 */
class MatchingEvaluationTest {

    private final MatchingDataset dataset = MatchingDatasetGenerator.defaultDataset();
    private final MatchingEvaluation.Report report = MatchingEvaluation.evaluate(dataset, new Matcher());

    @Test
    void dataset_has_300_payments_covering_every_rule_and_trap() {
        assertThat(dataset.cases()).hasSize(300);
        Map<Kind, Long> kinds = dataset.cases().stream()
                .collect(Collectors.groupingBy(MatchingDataset.Case::kind, Collectors.counting()));
        assertThat(kinds.keySet()).containsExactlyInAnyOrder(Kind.values());
        assertThat(MatchingDatasetGenerator.defaultDataset().cases()).extracting(c -> c.payment().amount())
                .as("deterministic for a seed")
                .containsExactlyElementsOf(dataset.cases().stream().map(c -> c.payment().amount()).toList());
    }

    @Test
    void B27_no_automatic_confirmation_is_wrong() {
        assertThat(report.wrongAutoConfirmations()).as(report.render()).isZero();
    }

    @Property(tries = 10)
    void B27_no_automatic_confirmation_is_wrong_for_other_seeds(@ForAll @LongRange(min = 1, max = 1_000_000) long seed) {
        MatchingEvaluation.Report other = MatchingEvaluation.evaluate(new MatchingDatasetGenerator(seed).generate(),
                new Matcher());

        assertThat(other.wrongAutoConfirmations()).as(other.render()).isZero();
    }

    @Test
    void B27_every_exact_structured_reference_is_confirmed_and_only_those() {
        assertThat(report.precisionByRule().get(MatchRule.R1).ratio()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.recallByKind().get(Kind.R1_QR_REFERENCE).ratio()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.recallByKind().get(Kind.R1_CREDITOR_REFERENCE).ratio()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void B28_ties_are_never_settled_by_choosing_one() {
        assertThat(report.recallByKind().get(Kind.TIE).ratio()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void B10_debits_and_payments_without_invoice_get_no_decision() {
        assertThat(report.recallByKind().get(Kind.DEBIT).ratio()).isEqualByComparingTo(BigDecimal.ONE);
        assertThat(report.recallByKind().get(Kind.NO_INVOICE).ratio()).isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void every_rule_stays_above_the_precision_floor() {
        assertThat(report.precisionByRule()).allSatisfy((rule, stats) ->
                assertThat(stats.ratio()).as("%s %s", rule, report.render()).isGreaterThanOrEqualTo(new BigDecimal("0.95")));
        assertThat(report.foundPayments().ratio()).isGreaterThanOrEqualTo(new BigDecimal("0.95"));
        assertThat(report.falseProposals()).isLessThanOrEqualTo(3);
    }
}
