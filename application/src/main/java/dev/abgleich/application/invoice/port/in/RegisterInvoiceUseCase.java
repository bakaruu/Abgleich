package dev.abgleich.application.invoice.port.in;

import dev.abgleich.application.invoice.DuplicateInvoiceException;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import java.util.UUID;

public interface RegisterInvoiceUseCase {

    /**
     * @return the id of the new invoice
     * @throws InvalidInvoiceException if the invoice breaks a business rule
     * @throws DuplicateInvoiceException if an invoice with the same number exists (B24)
     */
    UUID register(RegisterInvoiceCommand command);
}
