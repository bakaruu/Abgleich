package dev.abgleich.application.events.port.out;

import dev.abgleich.domain.invoice.InvoiceEvent;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Events waiting to be published. They are written by the repositories that store the change causing them,
 * in the same transaction, so an event exists exactly when its change was committed (B23).
 */
public interface OutboxRepositoryPort {

    /** Unpublished events in the order they were stored. */
    List<PendingEvent> findUnpublished(int limit);

    void markPublished(UUID eventId, Instant publishedAt);

    /** Counts a failed attempt and keeps a short reason; the event stays unpublished. */
    void markFailed(UUID eventId, String reason);

    long countUnpublished();

    /**
     * Deletes events published before the given instant. Unpublished events stay, whatever their age.
     *
     * @return the number of deleted events
     */
    int deletePublishedBefore(Instant publishedBefore);

    /** @param attempts failed publishing attempts so far */
    record PendingEvent(InvoiceEvent event, int attempts) {
        public PendingEvent {
            Objects.requireNonNull(event, "event");
            if (attempts < 0) {
                throw new IllegalArgumentException("attempts cannot be negative");
            }
        }
    }
}
