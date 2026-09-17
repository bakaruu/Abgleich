package dev.abgleich.adapter.in.rest;

import dev.abgleich.adapter.in.rest.ApiJson.InvoiceCreated;
import dev.abgleich.adapter.in.rest.ApiJson.RegisterInvoiceRequest;
import dev.abgleich.application.invoice.DuplicateInvoiceException;
import dev.abgleich.application.invoice.InvoiceReceipt;
import dev.abgleich.application.invoice.MessageChannel;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.application.invoice.port.in.CancelInvoiceUseCase;
import dev.abgleich.application.invoice.port.in.InvoiceQuery;
import dev.abgleich.application.invoice.port.in.ReceiveInvoiceUseCase;
import dev.abgleich.application.invoice.port.in.RegisterInvoiceUseCase;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/invoices")
class InvoiceApiController {

    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final RegisterInvoiceUseCase registerInvoice;
    private final ReceiveInvoiceUseCase receiveInvoice;
    private final CancelInvoiceUseCase cancelInvoice;
    private final InvoiceQuery invoices;

    InvoiceApiController(RegisterInvoiceUseCase registerInvoice, ReceiveInvoiceUseCase receiveInvoice,
            CancelInvoiceUseCase cancelInvoice, InvoiceQuery invoices) {
        this.registerInvoice = registerInvoice;
        this.receiveInvoice = receiveInvoice;
        this.cancelInvoice = cancelInvoice;
        this.invoices = invoices;
    }

    /**
     * Raw JSON becomes value objects here; invalid input never reaches the use case (B04, B05).
     *
     * <p>With an {@code Idempotency-Key}, a client can retry after a timeout without knowing whether the first
     * request arrived: the retry gets the same invoice id with 200 instead of 201, and nothing is stored twice
     * (B24). The same key with another invoice is refused with 422.
     */
    @PostMapping
    ResponseEntity<InvoiceCreated> register(@RequestBody RegisterInvoiceRequest request,
            @RequestHeader(name = IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        Currency currency = Currency.getInstance(required(request.currency(), "currency").toUpperCase(Locale.ROOT));
        RegisterInvoiceCommand command = new RegisterInvoiceCommand(
                InvoiceNumber.of(required(request.invoiceNumber(), "invoiceNumber")),
                Iban.of(required(request.creditorIban(), "creditorIban")),
                required(request.debtorName(), "debtorName"),
                Money.of(required(request.amount(), "amount"), currency),
                PaymentReference.parse(request.reference()),
                required(request.dueDate(), "dueDate"));
        if (idempotencyKey == null) {
            return ResponseEntity.status(HttpStatus.CREATED).body(new InvoiceCreated(registerInvoice.register(command)));
        }
        if (!ReceiveInvoiceUseCase.MESSAGE_ID.matcher(idempotencyKey).matches()) {
            throw new BadRequestException("Header '" + IDEMPOTENCY_KEY + "' must have 1 to 64 letters, digits or . _ : -");
        }
        InvoiceReceipt receipt = receiveInvoice.receive(MessageChannel.REST, idempotencyKey, command);
        return switch (receipt.outcome()) {
            case REGISTERED -> ResponseEntity.status(receipt.redelivered() ? HttpStatus.OK : HttpStatus.CREATED)
                    .header("Idempotent-Replayed", Boolean.toString(receipt.redelivered()))
                    .body(new InvoiceCreated(receipt.invoiceId()));
            case DUPLICATE_NUMBER -> throw new DuplicateInvoiceException("Invoice " + command.number() + " already exists", null);
            case ID_REUSED -> throw new IdempotencyKeyReusedException(
                    "This " + IDEMPOTENCY_KEY + " was already used for another invoice. Use a new key for a new invoice.");
        };
    }

    @GetMapping
    List<ApiJson.InvoiceJson> list(@RequestParam(required = false) InvoiceStatus status,
            @RequestParam(defaultValue = "200") int limit) {
        return invoices.list(status, limit).stream().map(ApiJson.InvoiceJson::of).toList();
    }

    /** The invoice with its allocation history; the ETag carries the version a cancellation must send. */
    @GetMapping("/{id}")
    ResponseEntity<ApiJson.InvoiceDetailJson> detail(@PathVariable UUID id) {
        return invoices.detail(id)
                .map(detail -> ResponseEntity.ok()
                        .eTag(ETags.of(detail.invoice().version()))
                        .body(ApiJson.InvoiceDetailJson.of(detail)))
                .orElseThrow(() -> new NotFoundException("No invoice with id " + id));
    }

    @PostMapping("/{id}/cancel")
    ResponseEntity<ApiJson.DecisionJson> cancel(@PathVariable UUID id,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return ReviewApiController.respond(cancelInvoice.cancel(id, ETags.version(ifMatch)));
    }

    private static <T> T required(T value, String field) {
        if (value == null || (value instanceof String text && text.isBlank())) {
            throw new BadRequestException("Field '" + field + "' is required");
        }
        return value;
    }
}
