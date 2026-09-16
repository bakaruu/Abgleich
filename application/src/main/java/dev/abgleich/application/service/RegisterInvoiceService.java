package dev.abgleich.application.service;

import dev.abgleich.application.port.in.RegisterInvoiceCommand;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.port.out.InvoiceRepositoryPort;
import dev.abgleich.domain.invoice.Invoice;
import java.util.Objects;
import java.util.UUID;

public final class RegisterInvoiceService implements RegisterInvoiceUseCase {

    private final InvoiceRepositoryPort invoices;

    public RegisterInvoiceService(InvoiceRepositoryPort invoices) {
        this.invoices = Objects.requireNonNull(invoices, "invoices");
    }

    @Override
    public UUID register(RegisterInvoiceCommand command) {
        Invoice invoice = Invoice.register(UUID.randomUUID(), command.number(), command.creditorAccount(),
                command.debtorName(), command.amount(), command.reference(), command.dueDate());
        invoices.add(invoice);
        return invoice.id();
    }
}
