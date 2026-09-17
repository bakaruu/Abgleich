package dev.abgleich.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SettlementTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");
    private static final UUID PAYMENT = UUID.randomUUID();

    @Test
    void B22_each_invoice_is_settled_from_the_version_that_was_read() {
        Invoice first = invoice("FV-2026-0091", "300.00");
        Invoice second = invoice("FV-2026-0092", "305.00");
        UUID group = UUID.randomUUID();

        List<Invoice> settled = Settlement.apply(List.of(confirmed(first, group, "300.00"), confirmed(second, group, "305.00")),
                List.of(first, second));

        assertThat(settled).extracting(Invoice::status).containsExactly(InvoiceStatus.PAID, InvoiceStatus.PAID);
        assertThat(settled).extracting(Invoice::version).containsExactly(first.version(), second.version());
    }

    @Test
    void B31_a_cancelled_invoice_cannot_be_settled() {
        Invoice cancelled = invoice("FV-2026-0093", "120.00").cancel();

        assertThatThrownBy(() -> Settlement.apply(List.of(confirmed(cancelled, UUID.randomUUID(), "120.00")),
                List.of(cancelled))).isInstanceOf(InvalidInvoiceException.class);
    }

    @Test
    void an_allocation_to_an_invoice_that_was_not_read_is_a_programming_error() {
        Invoice read = invoice("FV-2026-0094", "50.00");
        Invoice unread = invoice("FV-2026-0095", "50.00");

        assertThatThrownBy(() -> Settlement.apply(List.of(confirmed(unread, UUID.randomUUID(), "50.00")), List.of(read)))
                .isInstanceOf(NullPointerException.class);
    }

    private static Invoice invoice(String number, String amount) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), Iban.of("ES9121000418450200051332"),
                "Pinturas Sol SA", Money.eur(amount), PaymentReference.none(), LocalDate.of(2026, 9, 30));
    }

    private static Allocation confirmed(Invoice invoice, UUID group, String amount) {
        return Allocation.propose(PAYMENT, invoice.id(), group, Money.eur(amount), null, MatchRule.R6, "sum", NOW)
                .confirm(Allocation.SYSTEM, NOW);
    }
}
