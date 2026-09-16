package dev.abgleich.domain.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceEvent.InvoicePaid;
import dev.abgleich.domain.invoice.InvoiceEvent.InvoiceReopened;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceEventTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-00000000e001");

    private final Invoice open = Invoice.register(UUID.randomUUID(), InvoiceNumber.of("F-2026-0142"),
            Iban.of("CH9300762011623852957"), "Keller GmbH", Money.chf("480.00"), PaymentReference.none(),
            LocalDate.of(2026, 9, 30));

    @Test
    void settling_an_invoice_announces_it_as_paid() {
        Invoice paid = open.withConfirmedPayment(Money.chf("480.00"));

        assertThat(InvoiceEvent.between(open, paid, () -> EVENT_ID, NOW)).hasValueSatisfying(event -> {
            assertThat(event).isInstanceOf(InvoicePaid.class);
            assertThat(event.eventId()).isEqualTo(EVENT_ID);
            assertThat(event.paidAmount()).isEqualTo(Money.chf("480.00"));
            assertThat(event.status()).isEqualTo(InvoiceStatus.PAID);
            assertThat(event.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void overpaying_is_also_settling() {
        Invoice overpaid = open.withConfirmedPayment(Money.chf("500.00"));

        assertThat(InvoiceEvent.between(open, overpaid, () -> EVENT_ID, NOW))
                .hasValueSatisfying(event -> assertThat(event.status()).isEqualTo(InvoiceStatus.OVERPAID));
    }

    @Test
    void a_partial_payment_or_a_second_settling_payment_announces_nothing() {
        Invoice partial = open.withConfirmedPayment(Money.chf("100.00"));
        Invoice paid = partial.withConfirmedPayment(Money.chf("380.00"));
        Invoice overpaid = paid.withConfirmedPayment(Money.chf("10.00"));

        assertThat(InvoiceEvent.between(open, partial, UUID::randomUUID, NOW)).isEmpty();
        assertThat(InvoiceEvent.between(paid, overpaid, UUID::randomUUID, NOW)).isEmpty();
    }

    @Test
    void B10_reversing_the_payment_of_a_paid_invoice_reopens_it() {
        Invoice paid = open.withConfirmedPayment(Money.chf("480.00"));
        Invoice reversed = paid.withReversedPayment(Money.chf("480.00"));

        assertThat(InvoiceEvent.between(paid, reversed, () -> EVENT_ID, NOW)).hasValueSatisfying(event -> {
            assertThat(event).isInstanceOf(InvoiceReopened.class);
            assertThat(event.status()).isEqualTo(InvoiceStatus.OPEN);
        });
    }

    @Test
    void events_cannot_contradict_the_invoice_status() {
        assertThatThrownBy(() -> new InvoicePaid(EVENT_ID, open.id(), open.number(), open.creditorAccount(),
                open.amount(), open.paidAmount(), InvoiceStatus.OPEN, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InvoiceEvent.between(open, Invoice.register(UUID.randomUUID(), open.number(),
                open.creditorAccount(), "Other", open.amount(), null, open.dueDate()), UUID::randomUUID, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
