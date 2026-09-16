package dev.abgleich.domain.matching;

import dev.abgleich.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Money of one bank transaction assigned to one invoice, with the rule and the reason. Decided
 * allocations are never deleted; a confirmed one can only be reversed later.
 */
public record Allocation(
        UUID id,
        UUID transactionId,
        UUID invoiceId,
        Money amount,
        MatchRule rule,
        Confidence confidence,
        String explanation,
        AllocationStatus status,
        String decidedBy,
        Instant decidedAt,
        Instant createdAt,
        long version) {

    public static final String SYSTEM = "system";
    private static final int MAX_EXPLANATION_LENGTH = 500;

    public Allocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Allocated amount must be positive");
        }
        if (explanation.isBlank() || explanation.length() > MAX_EXPLANATION_LENGTH) {
            throw new IllegalArgumentException("Explanation must have between 1 and 500 characters");
        }
        boolean decided = decidedBy != null && decidedAt != null;
        if ((status == AllocationStatus.PROPOSED) == decided) {
            throw new IllegalArgumentException("Only decided allocations have a decider and a decision time");
        }
    }

    public static Allocation propose(UUID transactionId, UUID invoiceId, Money amount, MatchRule rule,
            Confidence confidence, String explanation, Instant createdAt) {
        return new Allocation(UUID.randomUUID(), transactionId, invoiceId, amount, rule, confidence, explanation,
                AllocationStatus.PROPOSED, null, null, createdAt, 0);
    }

    Allocation confirmedBySystem(Instant decidedAt) {
        if (status != AllocationStatus.PROPOSED) {
            throw new IllegalStateException("Only a proposed allocation can be confirmed");
        }
        return new Allocation(id, transactionId, invoiceId, amount, rule, confidence, explanation,
                AllocationStatus.CONFIRMED, SYSTEM, decidedAt, createdAt, version);
    }
}
