package dev.abgleich.application.port.in;

import java.util.Objects;

/**
 * The result of a person's decision: review a proposal, cancel an invoice. Expected situations are
 * results with a message to show, not exceptions.
 */
public record DecisionResult(Outcome outcome, String message) {

    public enum Outcome {
        /** The decision was stored. */
        DONE,
        /** The same decision was already stored, for example by a double click (B33). */
        ALREADY_DONE,
        /** Someone changed the data after it was shown; nothing was stored (B34). */
        STALE,
        NOT_FOUND,
        /** A business rule forbids it, for example confirming a payment to a cancelled invoice. */
        REFUSED
    }

    public static final String STALE_MESSAGE =
            "Someone else already decided on this payment. Reload to see its current state.";

    public DecisionResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(message, "message");
    }

    public static DecisionResult done(String message) {
        return new DecisionResult(Outcome.DONE, message);
    }

    public static DecisionResult stale() {
        return new DecisionResult(Outcome.STALE, STALE_MESSAGE);
    }
}
