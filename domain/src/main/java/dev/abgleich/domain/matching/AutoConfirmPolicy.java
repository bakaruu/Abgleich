package dev.abgleich.domain.matching;

import java.time.Instant;
import java.util.List;

/**
 * The only place that lets a match skip human review. A wrong automatic confirmation is worse than
 * no match at all (B27), so it takes rule R1, one invoice and no close competitor; the matcher sends
 * ties to review before asking (B28).
 */
public final class AutoConfirmPolicy {

    private AutoConfirmPolicy() {
    }

    /** @param proposal the allocations of one proposal group */
    public static ReconciliationDecision decide(MatchRule rule, List<Allocation> proposal, Instant now) {
        if (rule == MatchRule.R1 && proposal.size() == 1) {
            return new ReconciliationDecision.AutoConfirmed(List.of(proposal.getFirst().confirm(Allocation.SYSTEM, now)));
        }
        return new ReconciliationDecision.NeedsReview(proposal);
    }
}
