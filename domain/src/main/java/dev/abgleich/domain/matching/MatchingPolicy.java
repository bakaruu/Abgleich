package dev.abgleich.domain.matching;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * The thresholds of the matching rules in one place, so they can be calibrated against the labelled
 * dataset instead of being scattered through the code.
 *
 * @param maxChargesRatio a payment this share of the outstanding amount short can be bank charges (B07)
 * @param maxChargesAmount and at most this many currency units short
 * @param nameThreshold minimum payer-debtor name similarity for R5 and R6
 * @param dueDateWindowDays R5 only considers invoices due this many days before or after the booking
 * @param maxInvoicesInCombination R6 combines at most this many invoices (B29)
 * @param maxInvoicesSearched R6 searches combinations among at most this many invoices of one debtor (B29)
 * @param combinationBudget R6 stops after evaluating this many partial combinations for a payment (B29)
 * @param ambiguityMargin candidates closer than this in confidence are a tie that goes to review (B28)
 */
public record MatchingPolicy(
        BigDecimal maxChargesRatio,
        BigDecimal maxChargesAmount,
        BigDecimal nameThreshold,
        int dueDateWindowDays,
        int maxInvoicesInCombination,
        int maxInvoicesSearched,
        int combinationBudget,
        BigDecimal ambiguityMargin) {

    public MatchingPolicy {
        Objects.requireNonNull(maxChargesRatio, "maxChargesRatio");
        Objects.requireNonNull(maxChargesAmount, "maxChargesAmount");
        Objects.requireNonNull(nameThreshold, "nameThreshold");
        Objects.requireNonNull(ambiguityMargin, "ambiguityMargin");
        if (maxInvoicesInCombination < 2 || maxInvoicesSearched < maxInvoicesInCombination || combinationBudget < 1) {
            throw new IllegalArgumentException("R6 limits must allow at least one combination of two invoices");
        }
    }

    public static MatchingPolicy defaults() {
        return new MatchingPolicy(new BigDecimal("0.02"), new BigDecimal("30.00"), new BigDecimal("0.90"), 45, 5, 20,
                20_000, new BigDecimal("0.05"));
    }
}
