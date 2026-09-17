package dev.abgleich.adapter.in.rest;

import dev.abgleich.application.DecisionResult;

/** A decision that was not stored, turned into a Problem Detail by {@link ApiExceptionHandler}. */
final class DecisionRefusedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient DecisionResult result;

    DecisionRefusedException(DecisionResult result) {
        super(result.message());
        this.result = result;
    }

    DecisionResult result() {
        return result;
    }
}
