package dev.abgleich.application.port.out;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import java.util.Currency;
import java.util.List;

public interface InvoiceRepositoryPort {

    /** @throws DuplicateInvoiceException if the invoice number is already taken (B24) */
    void add(Invoice invoice);

    /** Invoices that still accept payments, ordered by invoice number. */
    List<Invoice> findOpen(Iban creditorAccount, Currency currency);
}
