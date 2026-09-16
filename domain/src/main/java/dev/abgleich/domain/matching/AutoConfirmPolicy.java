package dev.abgleich.domain.matching;

import java.time.Instant;
import java.util.List;

/**
 * The only place that lets a match skip human review. A wrong automatic confirmation is worse than
 * no match at all (B27), so it takes rule R1 and exactly one candidate. Anything else, including a
 * tie between equally good invoices, goes to review instead of being settled at random (B28).
 */
public final class AutoConfirmPolicy {

    private AutoConfirmPolicy() {
    }

    public static ReconciliationDecision decide(MatchRule rule, List<Allocation> proposals, Instant now) {
        if (rule == MatchRule.R1 && proposals.size() == 1) {
            return new ReconciliationDecision.AutoConfirmed(proposals.getFirst().confirmedBySystem(now));
        }
        return new ReconciliationDecision.NeedsReview(proposals);
    }
}
