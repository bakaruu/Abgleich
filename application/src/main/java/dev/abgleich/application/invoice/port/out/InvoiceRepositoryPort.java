package dev.abgleich.application.invoice.port.out;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.reference.PaymentReference;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepositoryPort {

    /** @throws DuplicateInvoiceException if the invoice number is already taken (B24) */
    void add(Invoice invoice);

    Optional<Invoice> findById(UUID id);

    /**
     * Invoices a payment could belong to: every invoice of the account and currency that still accepts
     * payments, plus closed invoices carrying the payment's structured reference, so a payment to a paid
     * or cancelled invoice goes to review instead of being lost (B31). Ordered by invoice number.
     */
    List<Invoice> findCandidates(Iban creditorAccount, Currency currency, PaymentReference reference);

    /**
     * Stores a changed invoice if nobody changed it since it was read.
     *
     * @throws StaleDataException if the stored version differs (B22)
     */
    void update(Invoice invoice);
}
