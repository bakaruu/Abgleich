package dev.abgleich.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.port.out.OutboxRepositoryPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OutboxRetentionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T03:00:00Z");

    @Test
    void deletes_events_published_before_the_retention_period() {
        List<Instant> cutoffs = new ArrayList<>();
        OutboxRepositoryPort outbox = new OutboxRepositoryPort() {
            @Override
            public List<PendingEvent> findUnpublished(int limit) {
                return List.of();
            }

            @Override
            public void markPublished(UUID eventId, Instant publishedAt) {
            }

            @Override
            public void markFailed(UUID eventId, String reason) {
            }

            @Override
            public long countUnpublished() {
                return 0;
            }

            @Override
            public int deletePublishedBefore(Instant publishedBefore) {
                cutoffs.add(publishedBefore);
                return 4;
            }
        };
        OutboxRetentionService service =
                new OutboxRetentionService(outbox, Duration.ofDays(30), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.purgePublished()).isEqualTo(4);
        assertThat(cutoffs).containsExactly(Instant.parse("2026-10-21T03:00:00Z"));
        assertThatThrownBy(() -> new OutboxRetentionService(outbox, Duration.ZERO, Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
