package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.abgleich.application.events.port.out.OutboxRepositoryPort.PendingEvent;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcOutboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcOutboxRepository outbox;
    private JdbcInvoiceRepository invoices;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.emptied();
        outbox = new JdbcOutboxRepository(TestDatabase.dataSource());
        invoices = new JdbcInvoiceRepository(TestDatabase.dataSource());
    }

    @Test
    void events_round_trip_and_come_back_in_the_order_they_were_stored() {
        InvoiceEvent paid = storePaid("F-2026-0142");
        InvoiceEvent paidLater = storePaid("F-2026-0141");

        List<PendingEvent> pending = outbox.findUnpublished(10);

        assertThat(pending).extracting(PendingEvent::event).containsExactly(paid, paidLater);
        assertThat(pending).extracting(PendingEvent::attempts).containsOnly(0);
        assertThat(outbox.countUnpublished()).isEqualTo(2);
        assertThat(outbox.findUnpublished(1)).extracting(PendingEvent::event).containsExactly(paid);
    }

    @Test
    void B23_published_events_are_not_returned_again_and_failures_are_counted() {
        InvoiceEvent first = storePaid("F-2026-0141");
        InvoiceEvent second = storePaid("F-2026-0142");

        outbox.markFailed(second.eventId(), "x".repeat(600));
        outbox.markFailed(second.eventId(), "broker unreachable");
        outbox.markPublished(first.eventId(), NOW);

        assertThat(outbox.findUnpublished(10)).singleElement().satisfies(pending -> {
            assertThat(pending.event()).isEqualTo(second);
            assertThat(pending.attempts()).isEqualTo(2);
        });
        assertThat(jdbc.queryForObject("select last_error from outbox_event where id = ?", String.class,
                second.eventId())).isEqualTo("broker unreachable");
        assertThat(outbox.countUnpublished()).isEqualTo(1);
    }

    @Test
    void B23_retention_deletes_old_published_events_and_never_unpublished_ones() {
        InvoiceEvent oldPublished = storePaid("F-2026-0141");
        InvoiceEvent recentPublished = storePaid("F-2026-0142");
        InvoiceEvent oldUnpublished = storePaid("F-2026-0143");
        outbox.markPublished(oldPublished.eventId(), NOW.minusSeconds(40 * 86_400));
        outbox.markPublished(recentPublished.eventId(), NOW.minusSeconds(86_400));
        jdbc.update("update outbox_event set occurred_at = occurred_at - interval '90 days' where id = ?",
                oldUnpublished.eventId());

        int deleted = outbox.deletePublishedBefore(NOW.minusSeconds(30 * 86_400));

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForList("select invoice_number from outbox_event order by invoice_number", String.class))
                .containsExactly("F-2026-0142", "F-2026-0143");
    }

    @Test
    void B23_the_database_refuses_an_event_that_contradicts_the_invoice_status() {
        InvoiceEvent paid = storePaid("F-2026-0142");

        Throwable refused = catchThrowable(() -> jdbc.update(
                "update outbox_event set invoice_status = 'OPEN' where id = ?", paid.eventId()));

        assertThat(refused).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_outbox_status_matches_type");
    }

    private InvoiceEvent storePaid(String number) {
        Invoice open = Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), Iban.of("CH9300762011623852957"),
                "Keller GmbH", Money.chf("480.00"), PaymentReference.none(), LocalDate.of(2026, 9, 30));
        invoices.add(open);
        InvoiceEvent event = InvoiceEvent.between(open, open.withConfirmedPayment(Money.chf("480.00")),
                UUID::randomUUID, NOW).orElseThrow();
        OutboxRows.insert(JdbcClient.create(TestDatabase.dataSource()), event);
        return event;
    }
}
