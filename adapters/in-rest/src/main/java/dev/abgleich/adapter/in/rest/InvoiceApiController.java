package dev.abgleich.adapter.in.rest;

import dev.abgleich.adapter.in.rest.ApiJson.InvoiceCreated;
import dev.abgleich.adapter.in.rest.ApiJson.RegisterInvoiceRequest;
import dev.abgleich.application.port.in.RegisterInvoiceCommand;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.util.Currency;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invoices")
class InvoiceApiController {

    private final RegisterInvoiceUseCase registerInvoice;

    InvoiceApiController(RegisterInvoiceUseCase registerInvoice) {
        this.registerInvoice = registerInvoice;
    }

    /** Raw JSON becomes value objects here; invalid input never reaches the use case (B04, B05). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    InvoiceCreated register(@RequestBody RegisterInvoiceRequest request) {
        Currency currency = Currency.getInstance(required(request.currency(), "currency").toUpperCase(Locale.ROOT));
        RegisterInvoiceCommand command = new RegisterInvoiceCommand(
                InvoiceNumber.of(required(request.invoiceNumber(), "invoiceNumber")),
                Iban.of(required(request.creditorIban(), "creditorIban")),
                required(request.debtorName(), "debtorName"),
                Money.of(required(request.amount(), "amount"), currency),
                PaymentReference.parse(request.reference()),
                required(request.dueDate(), "dueDate"));
        UUID id = registerInvoice.register(command);
        return new InvoiceCreated(id);
    }

    private static <T> T required(T value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new BadRequestException("Field '" + field + "' is required");
        }
        return value;
    }
}
