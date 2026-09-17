package dev.abgleich.application.reconciliation.port.in;

import dev.abgleich.application.reconciliation.ReviewItem;
import java.util.List;

/** Read side of the review queue. Bank texts are untrusted and must be escaped by any view (B32). */
public interface ReviewQueueQuery {

    /** Payments waiting for a decision, oldest booking first, with their proposals best first. */
    List<ReviewItem> pending(int limit);

    long pendingCount();
}
