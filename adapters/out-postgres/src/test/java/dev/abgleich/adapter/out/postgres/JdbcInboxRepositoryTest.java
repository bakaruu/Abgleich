package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.invoice.InvoiceReceipt;
import dev.abgleich.application.invoice.InvoiceReceipt.Outcome;
import dev.abgleich.application.invoice.MessageChannel;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcInboxRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcInboxRepository inbox;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.emptied();
        inbox = new JdbcInboxRepository(TestDatabase.dataSource(), TestDatabase.transactions());
    }

    @Test
    void B24_redelivered_message_registers_the_invoice_once() {
        Invoice invoice = invoice("F-2026-0142");

        InvoiceReceipt first = inbox.registerOnce(MessageChannel.KAFKA, "evt-1", invoice, NOW);
        InvoiceReceipt second = inbox.registerOnce(MessageChannel.KAFKA, "evt-1", invoice("F-2026-0142"), NOW);

        assertThat(first).isEqualTo(new InvoiceReceipt(Outcome.REGISTERED, invoice.id(), false));
        assertThat(second).isEqualTo(new InvoiceReceipt(Outcome.REGISTERED, invoice.id(), true));
        assertThat(count("invoice")).isEqualTo(1);
        assertThat(count("processed_message")).isEqualTo(1);
    }

    @Test
    void B24_concurrent_deliveries_of_the_same_message_store_exactly_one_invoice() throws Exception {
        int deliveries = 8;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(deliveries);
        try {
            List<CompletableFuture<InvoiceReceipt>> receipts = java.util.stream.IntStream.range(0, deliveries)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        await(start);
                        return inbox.registerOnce(MessageChannel.KAFKA, "evt-race", invoice("F-2026-0142"), NOW);
                    }, pool))
                    .toList();
            start.countDown();
            List<InvoiceReceipt> results = receipts.stream().map(CompletableFuture::join).toList();

            assertThat(results).filteredOn(receipt -> !receipt.redelivered()).hasSize(1);
            assertThat(results).extracting(InvoiceReceipt::outcome).containsOnly(Outcome.REGISTERED);
            assertThat(results).extracting(InvoiceReceipt::invoiceId).containsOnly(results.getFirst().invoiceId());
            assertThat(count("invoice")).isEqualTo(1);
            assertThat(count("processed_message")).isEqualTo(1);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void B24_same_invoice_number_in_another_message_stores_nothing_new() {
        Invoice original = invoice("F-2026-0142");
        inbox.registerOnce(MessageChannel.KAFKA, "evt-1", original, NOW);

        InvoiceReceipt receipt = inbox.registerOnce(MessageChannel.KAFKA, "evt-2", invoice("F-2026-0142"), NOW);
        InvoiceReceipt retried = inbox.registerOnce(MessageChannel.KAFKA, "evt-2", invoice("F-2026-0142"), NOW);

        assertThat(receipt).isEqualTo(new InvoiceReceipt(Outcome.DUPLICATE_NUMBER, original.id(), false));
        assertThat(retried).as("a retry gets the first answer again")
                .isEqualTo(new InvoiceReceipt(Outcome.DUPLICATE_NUMBER, original.id(), true));
        assertThat(count("invoice")).isEqualTo(1);
    }

    @Test
    void message_ids_are_unique_per_channel_and_a_reused_id_is_reported() {
        Invoice kafka = invoice("F-2026-0141");
        inbox.registerOnce(MessageChannel.KAFKA, "same-id", kafka, NOW);

        InvoiceReceipt rest = inbox.registerOnce(MessageChannel.REST, "same-id", invoice("F-2026-0142"), NOW);
        InvoiceReceipt reused = inbox.registerOnce(MessageChannel.REST, "same-id", invoice("F-2026-0143"), NOW);

        assertThat(rest.outcome()).isEqualTo(Outcome.REGISTERED);
        assertThat(reused).isEqualTo(new InvoiceReceipt(Outcome.ID_REUSED, rest.invoiceId(), true));
        assertThat(jdbc.queryForList("select invoice_number from invoice order by invoice_number", String.class))
                .containsExactly("F-2026-0141", "F-2026-0142");
    }

    private static Invoice invoice(String number) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), Iban.of("CH9300762011623852957"),
                "Keller GmbH", Money.chf("480.00"), PaymentReference.none(), LocalDate.of(2026, 9, 30));
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
