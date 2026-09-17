package dev.abgleich.application.invoice;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.money.Money;
import java.time.LocalDate;
import java.util.UUID;

/** One invoice as the invoices screen and API show it. */
public record InvoiceView(
        UUID id,
        String number,
        Iban creditorAccount,
        String debtorName,
        Money amount,
        Money paidAmount,
        InvoiceStatus status,
        LocalDate dueDate,
        String reference,
        long version) {

    public Money outstanding() {
        return amount.subtract(paidAmount);
    }
}
