package dev.abgleich.domain.matching;

import java.util.List;
import java.util.Objects;

/** What the matcher decided for one payment. */
public sealed interface ReconciliationDecision {

    /** No rule found an invoice; the payment stays unmatched. */
    record NoMatch(String reason) implements ReconciliationDecision {
        public NoMatch {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** One certain match, confirmed without a person: only rule R1 with a single invoice (B27). */
    record AutoConfirmed(List<Allocation> allocations) implements ReconciliationDecision {
        public AutoConfirmed {
            allocations = List.copyOf(allocations);
            if (allocations.isEmpty() || allocations.stream().anyMatch(a -> a.status() != AllocationStatus.CONFIRMED)) {
                throw new IllegalArgumentException("An auto-confirmed decision holds confirmed allocations");
            }
        }
    }

    /**
     * Proposals a person confirms or rejects, best first. Allocations with the same group id belong to
     * one proposal; several groups mean the candidates were too close to choose (B28).
     */
    record NeedsReview(List<Allocation> proposals) implements ReconciliationDecision {
        public NeedsReview {
            proposals = List.copyOf(proposals);
            if (proposals.isEmpty() || proposals.stream().anyMatch(p -> p.status() != AllocationStatus.PROPOSED)) {
                throw new IllegalArgumentException("A review needs at least one proposed allocation");
            }
        }

        public long groups() {
            return proposals.stream().map(Allocation::groupId).distinct().count();
        }
    }
}
