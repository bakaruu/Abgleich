package dev.abgleich.application.events.service;

import dev.abgleich.application.events.port.in.PurgePublishedEventsUseCase;
import dev.abgleich.application.events.port.out.OutboxRepositoryPort;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

public final class OutboxRetentionService implements PurgePublishedEventsUseCase {

    private final OutboxRepositoryPort outbox;
    private final Duration retention;
    private final Clock clock;

    public OutboxRetentionService(OutboxRepositoryPort outbox, Duration retention, Clock clock) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
        this.retention = Objects.requireNonNull(retention, "retention");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention must be positive");
        }
    }

    @Override
    public int purgePublished() {
        return outbox.deletePublishedBefore(clock.instant().minus(retention));
    }
}
