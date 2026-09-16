package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.abgleich.application.port.out.DuplicateInvoiceException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcInvoiceRepositoryTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));

    private JdbcTemplate jdbc;
    private JdbcInvoiceRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.emptied();
        repository = new JdbcInvoiceRepository(TestDatabase.dataSource());
    }

    @Test
    void stored_invoice_is_read_back_unchanged() {
        Invoice invoice = invoice("F-2026-0142", "480.00", SCOR);

        repository.add(invoice);

        assertThat(repository.findOpen(ACCOUNT, Money.CHF)).containsExactly(invoice);
    }

    @Test
    void B24_invoice_number_cannot_be_registered_twice() {
        repository.add(invoice("F-2026-0142", "480.00", SCOR));

        assertThat(catchThrowable(() -> repository.add(invoice("f-2026-0142", "99.00", PaymentReference.none()))))
                .isInstanceOf(DuplicateInvoiceException.class)
                .hasMessage("Invoice F-2026-0142 already exists");
    }

    @Test
    void only_invoices_that_accept_payments_are_open_in_number_order() {
        repository.add(invoice("F-3", "10.00", PaymentReference.none()));
        repository.add(invoice("F-1", "10.00", PaymentReference.none()));
        repository.add(invoice("F-PAID", "10.00", PaymentReference.none()));
        repository.add(invoice("F-CANCELLED", "10.00", PaymentReference.none()));
        jdbc.update("update invoice set status = 'PAID', paid_amount = amount where invoice_number = 'F-PAID'");
        jdbc.update("update invoice set status = 'CANCELLED' where invoice_number = 'F-CANCELLED'");

        assertThat(repository.findOpen(ACCOUNT, Money.CHF)).extracting(i -> i.number().value())
                .containsExactly("F-1", "F-3");
        assertThat(repository.findOpen(ACCOUNT, Money.EUR)).isEmpty();
    }

    private static Invoice invoice(String number, String amount, PaymentReference reference) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), ACCOUNT, "Keller GmbH",
                Money.chf(amount), reference, LocalDate.of(2026, 9, 30));
    }
}
