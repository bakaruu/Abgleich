package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.StaleDataException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.abgleich.application.invoice.DuplicateInvoiceException;
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

        assertThat(repository.findCandidates(ACCOUNT, Money.CHF, SCOR)).containsExactly(invoice);
        assertThat(repository.findById(invoice.id())).contains(invoice);
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

        assertThat(repository.findCandidates(ACCOUNT, Money.CHF, PaymentReference.none())).extracting(i -> i.number().value())
                .containsExactly("F-1", "F-3");
        assertThat(repository.findCandidates(ACCOUNT, Money.EUR, PaymentReference.none())).isEmpty();
    }

    @Test
    void B31_closed_invoices_with_the_payment_reference_are_candidates_too() {
        Invoice paid = invoice("F-2026-0142", "480.00", SCOR);
        repository.add(paid);
        repository.update(paid.withConfirmedPayment(Money.chf("480.00")));

        assertThat(repository.findCandidates(ACCOUNT, Money.CHF, SCOR)).extracting(Invoice::status)
                .containsExactly(dev.abgleich.domain.invoice.InvoiceStatus.PAID);
        assertThat(repository.findCandidates(ACCOUNT, Money.CHF, PaymentReference.none())).isEmpty();
    }

    @Test
    void B22_update_refuses_an_invoice_changed_since_it_was_read() {
        Invoice invoice = invoice("F-2026-0142", "480.00", SCOR);
        repository.add(invoice);
        repository.update(invoice.cancel());

        assertThat(catchThrowable(() -> repository.update(invoice.withConfirmedPayment(Money.chf("480.00")))))
                .isInstanceOf(dev.abgleich.application.StaleDataException.class);
        assertThat(repository.findById(invoice.id()).orElseThrow().version()).isEqualTo(1);
    }

    private static Invoice invoice(String number, String amount, PaymentReference reference) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), ACCOUNT, "Keller GmbH",
                Money.chf(amount), reference, LocalDate.of(2026, 9, 30));
    }
}
