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

    /** One certain match, confirmed without a human (only R1, B27). */
    record AutoConfirmed(Allocation allocation) implements ReconciliationDecision {
        public AutoConfirmed {
            Objects.requireNonNull(allocation, "allocation");
            if (allocation.status() != AllocationStatus.CONFIRMED) {
                throw new IllegalArgumentException("An auto-confirmed decision holds a confirmed allocation");
            }
        }
    }

    /** Proposals a person must confirm or reject, in a stable order (B28). */
    record NeedsReview(List<Allocation> proposals) implements ReconciliationDecision {
        public NeedsReview {
            proposals = List.copyOf(proposals);
            if (proposals.isEmpty() || proposals.stream().anyMatch(p -> p.status() != AllocationStatus.PROPOSED)) {
                throw new IllegalArgumentException("A review needs at least one proposed allocation");
            }
        }
    }
}
