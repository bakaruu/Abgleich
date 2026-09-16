package dev.abgleich.domain.matching;

import dev.abgleich.domain.invoice.Invoice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Decides what to do with one payment. Matching rules are domain logic, not an adapter (B40).
 *
 * <ol>
 *   <li>Debits never pay invoices (B10); invoices of another account or currency are ignored (B06).</li>
 *   <li>Rules R1 to R6 propose candidates; the same invoices proposed twice keep the stronger rule.</li>
 *   <li>Candidates are ranked deterministically (B28).</li>
 *   <li>If a runner-up is within the ambiguity margin, all close candidates go to review and none is
 *       chosen (B28). Otherwise the best one goes to the auto-confirm policy, which only accepts R1 (B27).</li>
 * </ol>
 */
public final class Matcher {

    static final int MAX_AMBIGUOUS_CANDIDATES = 5;

    private final MatchingPolicy policy;
    private final MatchingRules rules;

    public Matcher() {
        this(MatchingPolicy.defaults());
    }

    public Matcher(MatchingPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.rules = new MatchingRules(policy);
    }

    public ReconciliationDecision decide(PaymentToMatch payment, List<Invoice> invoices, Instant now) {
        return decide(payment, invoices, Set.of(), now);
    }

    /**
     * @param rejected invoice sets a person already rejected for this payment; they are never proposed again
     */
    public ReconciliationDecision decide(PaymentToMatch payment, List<Invoice> invoices, Set<Set<UUID>> rejected,
            Instant now) {
        Objects.requireNonNull(rejected, "rejected");
        Objects.requireNonNull(payment, "payment");
        Objects.requireNonNull(invoices, "invoices");
        Objects.requireNonNull(now, "now");
        if (!payment.direction().canPayInvoices()) {
            return new ReconciliationDecision.NoMatch("Debits never pay invoices");
        }
        List<Invoice> relevant = invoices.stream()
                .filter(invoice -> invoice.creditorAccount().equals(payment.account()))
                .filter(invoice -> invoice.amount().hasSameCurrencyAs(payment.amount()))
                .toList();
        List<Candidate> ranked = distinct(rules.candidates(payment, relevant).stream()
                .filter(candidate -> !rejected.contains(candidate.invoiceIds()))
                .sorted(Candidate.RANKING)
                .toList());
        if (ranked.isEmpty()) {
            return new ReconciliationDecision.NoMatch("No rule found an invoice for this payment");
        }

        Candidate best = ranked.getFirst();
        BigDecimal bestConfidence = best.rule().confidence().value();
        List<Candidate> close = ranked.stream()
                .filter(candidate -> bestConfidence.subtract(candidate.rule().confidence().value())
                        .compareTo(policy.ambiguityMargin()) < 0)
                .limit(MAX_AMBIGUOUS_CANDIDATES)
                .toList();
        if (close.size() > 1) {
            String tie = " (" + close.size() + " candidates are about equally likely: choose one)";
            return new ReconciliationDecision.NeedsReview(close.stream()
                    .flatMap(candidate -> proposal(payment, candidate, tie, now).stream())
                    .toList());
        }
        return AutoConfirmPolicy.decide(best.rule(), proposal(payment, best, "", now), now);
    }

    private static List<Candidate> distinct(List<Candidate> ranked) {
        Set<Set<UUID>> seen = new HashSet<>();
        List<Candidate> distinct = new ArrayList<>();
        for (Candidate candidate : ranked) {
            if (seen.add(candidate.invoiceIds())) {
                distinct.add(candidate);
            }
        }
        return distinct;
    }

    private static List<Allocation> proposal(PaymentToMatch payment, Candidate candidate, String suffix, Instant now) {
        UUID groupId = UUID.randomUUID();
        String explanation = truncate(candidate.explanation() + suffix);
        return candidate.shares().stream()
                .map(share -> Allocation.propose(payment.transactionId(), share.invoice().id(), groupId, share.amount(),
                        share.chargesWrittenOff(), candidate.rule(), explanation, now))
                .toList();
    }

    private static String truncate(String explanation) {
        return explanation.length() <= 500 ? explanation : explanation.substring(0, 497) + "...";
    }
}
