package dev.abgleich.domain.matching;

import dev.abgleich.domain.money.Money;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Money of one bank transaction assigned to one invoice, with the rule and the reason. Allocations
 * proposed together (one payment for several invoices, R6) share a {@code groupId} and are decided
 * together. Decided allocations are never deleted: a confirmed one can only be reversed, with a note.
 *
 * @param chargesWrittenOff bank charges accepted as paid when the payment is a little short (B07)
 * @param decisionNote why a person rejected it, or what reversed it
 */
public record Allocation(
        UUID id,
        UUID transactionId,
        UUID invoiceId,
        UUID groupId,
        Money amount,
        Money chargesWrittenOff,
        MatchRule rule,
        Confidence confidence,
        String explanation,
        AllocationStatus status,
        String decidedBy,
        Instant decidedAt,
        String decisionNote,
        Instant createdAt,
        long version) {

    public static final String SYSTEM = "system";
    /** Note of a proposal rejected because another proposal for the same payment was confirmed. */
    public static final String SUPERSEDED = "Another proposal for this payment was confirmed";
    private static final int MAX_TEXT_LENGTH = 500;

    public Allocation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(invoiceId, "invoiceId");
        Objects.requireNonNull(groupId, "groupId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(rule, "rule");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        chargesWrittenOff = Objects.requireNonNullElseGet(chargesWrittenOff, () -> Money.zero(amount.currency()));
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("Allocated amount must be positive");
        }
        if (!chargesWrittenOff.hasSameCurrencyAs(amount) || chargesWrittenOff.isNegative()) {
            throw new IllegalArgumentException("Charges written off must be zero or positive and in the same currency");
        }
        if (explanation.isBlank() || explanation.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("Explanation must have between 1 and 500 characters");
        }
        if (decisionNote != null && decisionNote.length() > MAX_TEXT_LENGTH) {
            throw new IllegalArgumentException("A decision note has at most 500 characters");
        }
        boolean decided = decidedBy != null && decidedAt != null;
        if ((status == AllocationStatus.PROPOSED) == decided) {
            throw new IllegalArgumentException("Only decided allocations have a decider and a decision time");
        }
    }

    public static Allocation propose(UUID transactionId, UUID invoiceId, UUID groupId, Money amount,
            Money chargesWrittenOff, MatchRule rule, String explanation, Instant createdAt) {
        return new Allocation(UUID.randomUUID(), transactionId, invoiceId, groupId, amount, chargesWrittenOff, rule,
                rule.confidence(), explanation, AllocationStatus.PROPOSED, null, null, null, createdAt, 0);
    }

    /** What the invoice counts as paid when this allocation is confirmed: the money plus accepted charges. */
    public Money settledAmount() {
        return amount.add(chargesWrittenOff);
    }

    public Allocation confirm(String decidedBy, Instant decidedAt) {
        requireStatus(AllocationStatus.PROPOSED, "confirmed");
        return decided(AllocationStatus.CONFIRMED, decidedBy, decidedAt, null);
    }

    public Allocation reject(String decidedBy, Instant decidedAt, String reason) {
        requireStatus(AllocationStatus.PROPOSED, "rejected");
        return decided(AllocationStatus.REJECTED, decidedBy, decidedAt, reason);
    }

    /** Undoes a confirmed allocation, for example when the bank reverses the payment (B10). */
    public Allocation reverse(String decidedBy, Instant decidedAt, String note) {
        requireStatus(AllocationStatus.CONFIRMED, "reversed");
        return decided(AllocationStatus.REVERSED, decidedBy, decidedAt, Objects.requireNonNull(note, "note"));
    }

    private void requireStatus(AllocationStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("A " + status + " allocation cannot be " + action);
        }
    }

    private Allocation decided(AllocationStatus newStatus, String by, Instant at, String note) {
        Objects.requireNonNull(by, "decidedBy");
        Objects.requireNonNull(at, "decidedAt");
        return new Allocation(id, transactionId, invoiceId, groupId, amount, chargesWrittenOff, rule, confidence,
                explanation, newStatus, by, at, note, createdAt, version);
    }
}
