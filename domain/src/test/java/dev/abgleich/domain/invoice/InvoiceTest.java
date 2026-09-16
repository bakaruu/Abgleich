package dev.abgleich.domain.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.CurrencyMismatchException;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.reference.QrReference;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceTest {

    private static final Iban QR_IBAN = Iban.of("CH4431999123000889012");
    private static final Iban REGULAR_IBAN = Iban.of("CH9300762011623852957");
    private static final PaymentReference QRR = new PaymentReference.Qrr(QrReference.of("210000000003139471430009017"));
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));

    @Test
    void B05_invoice_numbers_are_normalized() {
        assertThat(InvoiceNumber.of("  fv-2026-0087 ")).isEqualTo(InvoiceNumber.of("FV-2026-0087"));
        assertThatThrownBy(() -> InvoiceNumber.of("F<script>"))
                .isInstanceOf(InvalidInvoiceException.class);
        assertThatThrownBy(() -> InvoiceNumber.of("F".repeat(36)))
                .isInstanceOf(InvalidInvoiceException.class);
    }

    @Test
    void status_follows_the_confirmed_payments() {
        Invoice invoice = invoice("2000.00", QR_IBAN, QRR);
        assertThat(invoice.status()).isEqualTo(InvoiceStatus.OPEN);

        Invoice partial = invoice.withConfirmedPayment(Money.chf("1200.00"));
        assertThat(partial.status()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(partial.outstanding()).isEqualTo(Money.chf("800.00"));

        Invoice paid = partial.withConfirmedPayment(Money.chf("800.00"));
        assertThat(paid.status()).isEqualTo(InvoiceStatus.PAID);

        Invoice overpaid = paid.withConfirmedPayment(Money.chf("0.05"));
        assertThat(overpaid.status()).isEqualTo(InvoiceStatus.OVERPAID);
        assertThat(overpaid.outstanding()).isEqualTo(Money.chf("-0.05"));
    }

    @Test
    void B22_a_payment_keeps_the_version_the_invoice_was_read_at() {
        Invoice read = invoice("480.00", REGULAR_IBAN, SCOR);

        assertThat(read.withConfirmedPayment(Money.chf("480.00")).version()).isEqualTo(read.version());
    }

    @Test
    void cancelled_invoice_never_takes_money() {
        Invoice cancelled = new Invoice(UUID.randomUUID(), InvoiceNumber.of("F-1"), REGULAR_IBAN, "Keller GmbH",
                Money.chf("480.00"), SCOR, LocalDate.of(2026, 9, 30), Money.chf("0.00"), true, 3);

        assertThat(cancelled.status()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(cancelled.status().acceptsPayments()).isFalse();
        assertThatThrownBy(() -> cancelled.withConfirmedPayment(Money.chf("480.00")))
                .isInstanceOf(InvalidInvoiceException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void B10_reversed_payment_reopens_the_invoice() {
        Invoice paid = invoice("480.00", REGULAR_IBAN, SCOR).withConfirmedPayment(Money.chf("480.00"));

        Invoice reopened = paid.withReversedPayment(Money.chf("480.00"));

        assertThat(reopened.status()).isEqualTo(InvoiceStatus.OPEN);
        assertThatThrownBy(() -> reopened.withReversedPayment(Money.chf("0.01")))
                .isInstanceOf(InvalidInvoiceException.class);
    }

    @Test
    void B31_invoice_with_payments_cannot_be_cancelled() {
        Invoice partial = invoice("480.00", REGULAR_IBAN, SCOR).withConfirmedPayment(Money.chf("100.00"));

        assertThatThrownBy(partial::cancel).isInstanceOf(InvalidInvoiceException.class).hasMessageContaining("reverse them");
        assertThat(invoice("480.00", REGULAR_IBAN, SCOR).cancel().status()).isEqualTo(InvoiceStatus.CANCELLED);
    }

    @Test
    void B06_payment_in_another_currency_is_rejected() {
        Invoice invoice = invoice("480.00", REGULAR_IBAN, SCOR);

        assertThatThrownBy(() -> invoice.withConfirmedPayment(Money.eur("480.00")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void qr_reference_requires_a_qr_iban_and_creditor_reference_a_regular_iban() {
        assertThatThrownBy(() -> invoice("480.00", REGULAR_IBAN, QRR))
                .isInstanceOf(InvalidInvoiceException.class).hasMessageContaining("requires a QR-IBAN");
        assertThatThrownBy(() -> invoice("480.00", QR_IBAN, SCOR))
                .isInstanceOf(InvalidInvoiceException.class).hasMessageContaining("only accepts QR references");
        assertThatThrownBy(() -> invoice("480.00", REGULAR_IBAN, new PaymentReference.FreeText("FV-87")))
                .isInstanceOf(InvalidInvoiceException.class);
        assertThat(invoice("480.00", REGULAR_IBAN, PaymentReference.none()).reference())
                .isEqualTo(PaymentReference.none());
    }

    @Test
    void B41_to_string_leaves_out_the_debtor_name() {
        assertThat(invoice("480.00", REGULAR_IBAN, SCOR).toString()).doesNotContain("Keller").contains("F-2026-0142");
    }

    private static Invoice invoice(String amount, Iban creditor, PaymentReference reference) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of("F-2026-0142"), creditor, "Keller GmbH",
                Money.chf(amount), reference, LocalDate.of(2026, 9, 30));
    }
}
