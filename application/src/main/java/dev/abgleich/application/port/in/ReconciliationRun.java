package dev.abgleich.application.port.in;

/**
 * Counts of one reconciliation run.
 *
 * @param handledElsewhere payments another run decided while this one was working on them (B22)
 * @param deferred payments that kept conflicting with concurrent changes; the next run retries them
 */
public record ReconciliationRun(
        int examined,
        int autoConfirmed,
        int sentToReview,
        int unmatched,
        int handledElsewhere,
        int deferred) {
}
