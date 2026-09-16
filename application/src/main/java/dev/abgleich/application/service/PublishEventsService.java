package dev.abgleich.application.service;

import dev.abgleich.application.port.in.PublishEventsUseCase;
import dev.abgleich.application.port.out.EventPublisherPort;
import dev.abgleich.application.port.out.EventPublishingException;
import dev.abgleich.application.port.out.OutboxRepositoryPort;
import dev.abgleich.application.port.out.OutboxRepositoryPort.PendingEvent;
import java.time.Clock;
import java.util.Objects;

/**
 * The outbox relay (B23). The only state is the outbox table: after a crash or a restart the next run starts
 * from what the database says is unpublished.
 *
 * <p>An event is marked published after the broker acknowledged it. A crash between both steps publishes the
 * event again with the same event id, so delivery is at least once and consumers deduplicate by that id. The
 * run stops at the first failure: publishing later events first would let a consumer see "reopened" before
 * "paid" for the same invoice.
 */
public final class PublishEventsService implements PublishEventsUseCase {

    static final int BATCH_SIZE = 100;
    private static final int MAX_REASON_LENGTH = 500;

    private final OutboxRepositoryPort outbox;
    private final EventPublisherPort publisher;
    private final Clock clock;

    public PublishEventsService(OutboxRepositoryPort outbox, EventPublisherPort publisher, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public PublishRun publishPending() {
        int published = 0;
        for (PendingEvent pending : outbox.findUnpublished(BATCH_SIZE)) {
            try {
                publisher.publish(pending.event());
            } catch (EventPublishingException failure) {
                outbox.markFailed(pending.event().eventId(), reason(failure));
                return new PublishRun(published, 1, outbox.countUnpublished());
            }
            outbox.markPublished(pending.event().eventId(), clock.instant());
            published++;
        }
        return new PublishRun(published, 0, outbox.countUnpublished());
    }

    private static String reason(EventPublishingException failure) {
        String message = Objects.requireNonNullElse(failure.getMessage(), failure.getClass().getSimpleName());
        return message.length() <= MAX_REASON_LENGTH ? message : message.substring(0, MAX_REASON_LENGTH);
    }
}
