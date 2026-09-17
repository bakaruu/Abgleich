package dev.abgleich.application.invoice;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** Already validated value objects: adapters turn raw input into these before calling the use case. */
public record RegisterInvoiceCommand(
        InvoiceNumber number,
        Iban creditorAccount,
        String debtorName,
        Money amount,
        PaymentReference reference,
        LocalDate dueDate) {

    public RegisterInvoiceCommand {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(creditorAccount, "creditorAccount");
        Objects.requireNonNull(debtorName, "debtorName");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(dueDate, "dueDate");
    }

    /** The new open invoice, the same whether it arrives through the web, REST or Kafka. */
    public Invoice newInvoice(UUID id) {
        return Invoice.register(id, number, creditorAccount, debtorName, amount, reference, dueDate);
    }
}
