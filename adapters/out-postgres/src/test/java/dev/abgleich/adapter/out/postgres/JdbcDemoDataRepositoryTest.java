package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.invoice.InvoiceReceipt;
import dev.abgleich.application.invoice.MessageChannel;
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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcDemoDataRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

    @Test
    void B43_demo_data_is_purged_from_every_table_but_the_migration_history_and_the_locks() {
        JdbcTemplate jdbc = TestDatabase.emptied();
        Invoice invoice = Invoice.register(UUID.randomUUID(), InvoiceNumber.of("F-2026-0142"),
                Iban.of("CH9300762011623852957"), "A visitor's real customer", Money.chf("480.00"),
                PaymentReference.none(), LocalDate.of(2026, 9, 30));
        InvoiceReceipt receipt = new JdbcInboxRepository(TestDatabase.dataSource(), TestDatabase.transactions())
                .registerOnce(MessageChannel.REST, "visitor-key", invoice, NOW);
        OutboxRows.insert(JdbcClient.create(TestDatabase.dataSource()), InvoiceEvent.between(invoice,
                invoice.withConfirmedPayment(Money.chf("480.00")), UUID::randomUUID, NOW).orElseThrow());
        jdbc.update("insert into shedlock (name, lock_until, locked_at, locked_by) values ('demo-reset', now(), now(), 'test')");
        assertThat(receipt.outcome()).isEqualTo(InvoiceReceipt.Outcome.REGISTERED);
        assertThat(rows(jdbc, "invoice")).isEqualTo(1);

        assertThat(JdbcDemoDataRepository.KEPT_TABLES).as("only technical tables survive a reset")
                .containsExactlyInAnyOrder("flyway_schema_history", "shedlock");
        JdbcDemoDataRepository demo = new JdbcDemoDataRepository(TestDatabase.dataSource());
        assertThat(demo.storedBytes()).isPositive();
        demo.deleteAllData();

        List<String> tables = jdbc.queryForList("""
                select table_name from information_schema.tables
                 where table_schema = current_schema() and table_type = 'BASE TABLE'
                """, String.class);
        assertThat(tables).contains("invoice", "processed_message", "outbox_event", "statement_import");
        assertThat(tables).allSatisfy(table -> {
            if (JdbcDemoDataRepository.KEPT_TABLES.contains(table)) {
                assertThat(rows(jdbc, table)).as(table + " is kept").isPositive();
            } else {
                assertThat(rows(jdbc, table)).as(table + " is emptied").isZero();
            }
        });
        jdbc.update("delete from shedlock");
    }

    private static long rows(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("select count(*) from \"" + table + "\"", Long.class);
    }
}
