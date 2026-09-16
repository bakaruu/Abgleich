package dev.abgleich.adapter.out.synthetic.evaluation;

import dev.abgleich.adapter.out.synthetic.evaluation.MatchingDataset.Case;
import dev.abgleich.adapter.out.synthetic.evaluation.MatchingDataset.Kind;
import dev.abgleich.adapter.out.synthetic.evaluation.MatchingDataset.Outcome;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.matching.Matcher;
import dev.abgleich.domain.matching.ReconciliationDecision;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Runs the matcher over a labelled dataset and measures what matters for B27: how often an automatic
 * confirmation is wrong (must be never), how precise each rule's top proposal is, and how many
 * payments the matcher finds at all.
 */
public final class MatchingEvaluation {

    private MatchingEvaluation() {
    }

    public static Report evaluate(MatchingDataset dataset, Matcher matcher) {
        Instant now = MatchingDatasetGenerator.DAY.atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        List<Result> results = new ArrayList<>();
        for (Case example : dataset.cases()) {
            List<Invoice> sameAccount = dataset.invoices().stream()
                    .filter(invoice -> invoice.creditorAccount().equals(example.payment().account()))
                    .toList();
            results.add(result(example, matcher.decide(example.payment(), sameAccount, now)));
        }
        return new Report(results);
    }

    private static Result result(Case example, ReconciliationDecision decision) {
        return switch (decision) {
            case ReconciliationDecision.NoMatch noMatch -> new Result(example, Outcome.NONE, null, false, false, 0);
            case ReconciliationDecision.AutoConfirmed auto -> {
                Set<UUID> chosen = invoiceIds(auto.allocations());
                boolean correct = example.acceptable().contains(chosen);
                yield new Result(example, Outcome.AUTO, auto.allocations().getFirst().rule(), correct, correct, 1);
            }
            case ReconciliationDecision.NeedsReview review -> {
                Map<UUID, List<Allocation>> groups = review.proposals().stream().collect(Collectors.groupingBy(
                        Allocation::groupId, LinkedHashMap::new, Collectors.toList()));
                List<Set<UUID>> proposed = groups.values().stream().map(MatchingEvaluation::invoiceIds).toList();
                boolean topCorrect = example.acceptable().contains(proposed.getFirst());
                boolean anyCorrect = proposed.stream().anyMatch(example.acceptable()::contains);
                yield new Result(example, Outcome.REVIEW, review.proposals().getFirst().rule(), topCorrect, anyCorrect,
                        groups.size());
            }
        };
    }

    private static Set<UUID> invoiceIds(List<Allocation> allocations) {
        return allocations.stream().map(Allocation::invoiceId).collect(Collectors.toSet());
    }

    /**
     * @param rule the rule of the automatic confirmation or of the best proposal, {@code null} without a decision
     * @param topCorrect the confirmation or best proposal settles an acceptable invoice set
     * @param anyCorrect one of the proposals does
     */
    public record Result(Case example, Outcome outcome, MatchRule rule, boolean topCorrect, boolean anyCorrect,
            int proposals) {

        /** An automatic confirmation that settles the wrong invoices, or any invoice when none is right (B27). */
        public boolean wrongAutoConfirmation() {
            return outcome == Outcome.AUTO && !topCorrect;
        }

        /** Found the right invoice, either confirmed or as the best proposal. */
        public boolean found() {
            return !example.acceptable().isEmpty() && topCorrect;
        }

        public boolean falseProposal() {
            return example.acceptable().isEmpty() && outcome != Outcome.NONE;
        }
    }

    public record Report(List<Result> results) {

        public Report {
            results = List.copyOf(results);
        }

        public long wrongAutoConfirmations() {
            return results.stream().filter(Result::wrongAutoConfirmation).count();
        }

        /** Of the decisions a rule made (confirmation or best proposal), the share that was right. */
        public Map<MatchRule, Stats> precisionByRule() {
            Map<MatchRule, Stats> byRule = new EnumMap<>(MatchRule.class);
            for (Result result : results) {
                if (result.rule() != null) {
                    byRule.merge(result.rule(), Stats.of(result.topCorrect()), Stats::plus);
                }
            }
            return byRule;
        }

        /** Of the payments of each kind, the share with the expected outcome and the right invoices. */
        public Map<Kind, Stats> recallByKind() {
            Map<Kind, Stats> byKind = new EnumMap<>(Kind.class);
            for (Result result : results) {
                Kind kind = result.example().kind();
                boolean expected = result.outcome() == kind.expected()
                        && (kind.expected() == Outcome.NONE || (kind == Kind.TIE ? result.anyCorrect() && result.proposals() > 1
                                : result.topCorrect()));
                byKind.merge(kind, Stats.of(expected), Stats::plus);
            }
            return byKind;
        }

        public long falseProposals() {
            return results.stream().filter(Result::falseProposal).count();
        }

        public Stats overallPrecision() {
            return results.stream().filter(result -> result.rule() != null)
                    .map(result -> Stats.of(result.topCorrect())).reduce(Stats.ZERO, Stats::plus);
        }

        public Stats foundPayments() {
            return results.stream().filter(result -> !result.example().acceptable().isEmpty())
                    .map(result -> Stats.of(result.found())).reduce(Stats.ZERO, Stats::plus);
        }

        public String render() {
            StringBuilder out = new StringBuilder();
            out.append("Matching evaluation: ").append(results.size()).append(" labelled payments\n\n");
            out.append(String.format("%-26s %9s %9s%n", "Precision by rule", "correct", "precision"));
            precisionByRule().forEach((rule, stats) -> out.append(String.format("  %-24s %4d/%-4d %9s%n",
                    rule + " (" + rule.confidence().value() + ")", stats.hits(), stats.total(), stats.percent())));
            out.append(String.format("%n%-26s %9s %9s%n", "Expected outcome by case", "as label", "rate"));
            recallByKind().forEach((kind, stats) -> out.append(String.format("  %-24s %4d/%-4d %9s%n",
                    kind.name().toLowerCase(java.util.Locale.ROOT), stats.hits(), stats.total(), stats.percent())));
            out.append(String.format("%nOverall precision of decisions   %s (%d/%d)%n", overallPrecision().percent(),
                    overallPrecision().hits(), overallPrecision().total()));
            out.append(String.format("Payments with the right invoice  %s (%d/%d)%n", foundPayments().percent(),
                    foundPayments().hits(), foundPayments().total()));
            out.append("Proposals for payments without invoice  ").append(falseProposals()).append('\n');
            out.append("Wrong automatic confirmations    ").append(wrongAutoConfirmations())
                    .append(wrongAutoConfirmations() == 0 ? "  (B27 holds)" : "  (B27 VIOLATED)").append('\n');
            results.stream().filter(result -> result.wrongAutoConfirmation() || result.falseProposal()
                            || (!result.example().acceptable().isEmpty() && !result.anyCorrect()))
                    .forEach(result -> out.append("  ! ").append(result.example().id()).append(' ')
                            .append(result.example().kind()).append(" -> ").append(result.outcome())
                            .append(result.rule() == null ? "" : " " + result.rule()).append('\n'));
            return out.toString();
        }
    }

    public record Stats(int hits, int total) {

        static final Stats ZERO = new Stats(0, 0);

        static Stats of(boolean hit) {
            return new Stats(hit ? 1 : 0, 1);
        }

        Stats plus(Stats other) {
            return new Stats(hits + other.hits, total + other.total);
        }

        /** A ratio for reading, from integers: no floating point near anything that decides (B01). */
        public BigDecimal ratio() {
            return total == 0 ? BigDecimal.ONE : BigDecimal.valueOf(hits).divide(BigDecimal.valueOf(total), 4,
                    RoundingMode.DOWN);
        }

        public String percent() {
            return ratio().movePointRight(2).setScale(1, RoundingMode.DOWN) + " %";
        }
    }
}
