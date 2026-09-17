package dev.abgleich.application.reporting;

import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How well reconciliation works right now: what the machine settled on its own, what waits for a person, how
 * much money is still unidentified and how often people agree with each rule. Only credits count as payments;
 * debits never pay invoices (B10).
 *
 * @param unmatchedAmounts money of unmatched credits, one entry per currency, ordered by currency code
 * @param waitingForReviewAmounts money of credits with proposals, one entry per currency
 * @param rules every rule, in order R1 to R6, also those without allocations
 * @param imports imported statement files per channel, only channels that delivered any
 * @param outboxPending events not published yet (B23)
 */
public record ReconciliationSummary(
        Payments payments,
        List<Money> unmatchedAmounts,
        List<Money> waitingForReviewAmounts,
        List<RuleOutcome> rules,
        List<ChannelImports> imports,
        long outboxPending) {

    public ReconciliationSummary {
        Objects.requireNonNull(payments, "payments");
        unmatchedAmounts = List.copyOf(unmatchedAmounts);
        waitingForReviewAmounts = List.copyOf(waitingForReviewAmounts);
        rules = List.copyOf(rules);
        imports = List.copyOf(imports);
        if (!rules.stream().map(RuleOutcome::rule).toList().equals(List.of(MatchRule.values()))) {
            throw new IllegalArgumentException("A summary lists every rule once, in order");
        }
        requireNotNegative(outboxPending, "outboxPending");
    }

    /**
     * @param autoConfirmed matched by rule R1 without a person (B27)
     * @param confirmedByReview matched after a person confirmed a proposal
     * @param reversed matched credits the bank reversed afterwards (B10)
     */
    public record Payments(long credits, long autoConfirmed, long confirmedByReview, long waitingForReview,
            long unmatched, long reversed) {

        public Payments {
            requireNotNegative(credits, "credits");
            requireNotNegative(autoConfirmed, "autoConfirmed");
            requireNotNegative(confirmedByReview, "confirmedByReview");
            requireNotNegative(waitingForReview, "waitingForReview");
            requireNotNegative(unmatched, "unmatched");
            requireNotNegative(reversed, "reversed");
        }

        /** Share of credits settled without a person, in percent with one decimal; 0 without credits. */
        public BigDecimal autoReconciledPercent() {
            return percent(autoConfirmed, credits).orElse(BigDecimal.ZERO.setScale(1));
        }
    }

    /**
     * Proposal groups per rule; a payment for several invoices (R6) counts once.
     *
     * @param confirmed confirmed by a person
     * @param rejected rejected by a person
     * @param pending still waiting for a decision
     */
    public record RuleOutcome(MatchRule rule, long autoConfirmed, long confirmed, long rejected, long pending) {

        public RuleOutcome {
            Objects.requireNonNull(rule, "rule");
            requireNotNegative(autoConfirmed, "autoConfirmed");
            requireNotNegative(confirmed, "confirmed");
            requireNotNegative(rejected, "rejected");
            requireNotNegative(pending, "pending");
        }

        /**
         * How often people agreed with the rule: confirmed out of decided proposals, in percent. Empty while
         * nobody decided one, because 0 % and "no data" are different answers.
         */
        public Optional<BigDecimal> reviewerAgreementPercent() {
            return percent(confirmed, confirmed + rejected);
        }
    }

    public record ChannelImports(ImportSource source, long files) {
        public ChannelImports {
            Objects.requireNonNull(source, "source");
            requireNotNegative(files, "files");
        }
    }

    private static Optional<BigDecimal> percent(long part, long whole) {
        if (whole == 0) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(part).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP));
    }

    private static void requireNotNegative(long value, String name) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " cannot be negative");
        }
    }
}
