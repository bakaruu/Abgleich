package dev.abgleich.application.events.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.abgleich.application.events.port.in.PublishEventsUseCase.PublishRun;
import dev.abgleich.application.events.port.out.EventPublisherPort;
import dev.abgleich.application.events.port.out.EventPublishingException;
import dev.abgleich.application.events.port.out.OutboxRepositoryPort;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishEventsServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    /** The outbox table: the only thing that survives a restart. */
    private final FakeOutbox outbox = new FakeOutbox();

    @Test
    void publishes_pending_events_in_order_and_marks_them() {
        InvoiceEvent first = paid("F-2026-0141");
        InvoiceEvent second = paid("F-2026-0142");
        outbox.add(first);
        outbox.add(second);
        RecordingBroker broker = new RecordingBroker();

        PublishRun run = new PublishEventsService(outbox, broker, CLOCK).publishPending();

        assertThat(run).isEqualTo(new PublishRun(2, 0, 0));
        assertThat(broker.received).containsExactly(first, second);
        assertThat(outbox.publishedAt).containsEntry(first.eventId(), NOW).containsEntry(second.eventId(), NOW);
    }

    @Test
    void B23_event_survives_crash_after_commit() {
        // The decision committed with its event; the broker was down, then the application restarted.
        InvoiceEvent event = paid("F-2026-0142");
        outbox.add(event);
        RecordingBroker down = new RecordingBroker();
        down.failing = true;

        PublishRun beforeRestart = new PublishEventsService(outbox, down, CLOCK).publishPending();

        assertThat(beforeRestart).isEqualTo(new PublishRun(0, 1, 1));
        assertThat(outbox.attempts).containsEntry(event.eventId(), 1);
        assertThat(outbox.reasons.get(event.eventId())).isEqualTo("broker unreachable");

        RecordingBroker back = new RecordingBroker();
        PublishRun afterRestart = new PublishEventsService(outbox, back, CLOCK).publishPending();

        assertThat(afterRestart).isEqualTo(new PublishRun(1, 0, 0));
        assertThat(back.received).containsExactly(event);
    }

    @Test
    void B23_crash_between_acknowledgement_and_marking_publishes_the_same_event_again() {
        InvoiceEvent event = paid("F-2026-0142");
        outbox.add(event);
        RecordingBroker broker = new RecordingBroker();
        outbox.crashOnMarkPublished = true;

        assertThat(catchThrowable(() -> new PublishEventsService(outbox, broker, CLOCK).publishPending()))
                .isInstanceOf(IllegalStateException.class);
        outbox.crashOnMarkPublished = false;
        new PublishEventsService(outbox, broker, CLOCK).publishPending();

        assertThat(broker.received).as("at least once: consumers deduplicate by event id")
                .extracting(InvoiceEvent::eventId).containsExactly(event.eventId(), event.eventId());
        assertThat(outbox.countUnpublished()).isZero();
    }

    @Test
    void B23_stops_at_the_first_failure_so_later_events_do_not_overtake_it() {
        InvoiceEvent first = paid("F-2026-0141");
        InvoiceEvent second = paid("F-2026-0142");
        outbox.add(first);
        outbox.add(second);
        RecordingBroker broker = new RecordingBroker();
        broker.failFor = first.eventId();

        PublishRun run = new PublishEventsService(outbox, broker, CLOCK).publishPending();

        assertThat(run).isEqualTo(new PublishRun(0, 1, 2));
        assertThat(broker.received).isEmpty();
    }

    private static InvoiceEvent paid(String number) {
        Invoice open = Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), Iban.of("CH9300762011623852957"),
                "Keller GmbH", Money.chf("480.00"), PaymentReference.none(), LocalDate.of(2026, 9, 30));
        return InvoiceEvent.between(open, open.withConfirmedPayment(Money.chf("480.00")), UUID::randomUUID, NOW)
                .orElseThrow();
    }

    private static final class RecordingBroker implements EventPublisherPort {
        private final List<InvoiceEvent> received = new ArrayList<>();
        private boolean failing;
        private UUID failFor;

        @Override
        public void publish(InvoiceEvent event) {
            if (failing || event.eventId().equals(failFor)) {
                throw new EventPublishingException("broker unreachable", null);
            }
            received.add(event);
        }
    }

    private static final class FakeOutbox implements OutboxRepositoryPort {
        private final Map<UUID, InvoiceEvent> events = new LinkedHashMap<>();
        private final Map<UUID, Instant> publishedAt = new HashMap<>();
        private final Map<UUID, Integer> attempts = new HashMap<>();
        private final Map<UUID, String> reasons = new HashMap<>();
        private boolean crashOnMarkPublished;

        void add(InvoiceEvent event) {
            events.put(event.eventId(), event);
        }

        @Override
        public List<PendingEvent> findUnpublished(int limit) {
            return events.values().stream()
                    .filter(event -> !publishedAt.containsKey(event.eventId()))
                    .limit(limit)
                    .map(event -> new PendingEvent(event, attempts.getOrDefault(event.eventId(), 0)))
                    .toList();
        }

        @Override
        public void markPublished(UUID eventId, Instant at) {
            if (crashOnMarkPublished) {
                throw new IllegalStateException("process killed");
            }
            publishedAt.put(eventId, at);
        }

        @Override
        public void markFailed(UUID eventId, String reason) {
            attempts.merge(eventId, 1, Integer::sum);
            reasons.put(eventId, reason);
        }

        @Override
        public long countUnpublished() {
            return events.keySet().stream().filter(id -> !publishedAt.containsKey(id)).count();
        }

        @Override
        public int deletePublishedBefore(Instant publishedBefore) {
            throw new UnsupportedOperationException("not used by the relay");
        }
    }
}
