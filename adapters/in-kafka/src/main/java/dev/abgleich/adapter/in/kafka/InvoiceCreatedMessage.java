package dev.abgleich.adapter.in.kafka;

import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.InvalidReferenceException;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code InvoiceCreated} event, version 1:
 *
 * <pre>{@code
 * {"specVersion": 1, "eventId": "5f0c...", "invoiceNumber": "F-2026-0142", "creditorIban": "CH93...",
 *  "debtorName": "Keller GmbH", "amount": "1250.00", "currency": "CHF", "reference": "RF18...", "dueDate": "2026-09-30"}
 * }</pre>
 *
 * Raw JSON becomes validated value objects here. The amount must be a string: a JSON number may already have
 * passed through floating point in the sender (B01).
 */
record InvoiceCreatedMessage(String eventId, RegisterInvoiceCommand command) {

    static final int MAX_BYTES = 16 * 1024;
    private static final Pattern DECIMAL = Pattern.compile("\\d{1,17}(\\.\\d{1,2})?");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    InvoiceCreatedMessage {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(command, "command");
    }

    /** @throws RejectedMessageException if the message is not a valid version 1 event */
    static InvoiceCreatedMessage parse(String value) {
        if (value == null || value.length() > MAX_BYTES) {
            throw new RejectedMessageException("The message is empty or larger than " + MAX_BYTES + " characters");
        }
        JsonNode json;
        try {
            json = JSON.readTree(value);
        } catch (JacksonException e) {
            throw new RejectedMessageException("The message is not valid JSON", e);
        }
        if (json == null || !json.isObject()) {
            throw new RejectedMessageException("The message is not a JSON object");
        }
        if (!json.path("specVersion").isNumber() || json.path("specVersion").intValue() != 1) {
            throw new RejectedMessageException("Only specVersion 1 is supported");
        }
        try {
            String amount = text(json, "amount");
            if (!DECIMAL.matcher(amount).matches()) {
                throw new RejectedMessageException("Field 'amount' must be a decimal string such as \"1250.00\"");
            }
            Currency currency = Currency.getInstance(text(json, "currency").toUpperCase(Locale.ROOT));
            RegisterInvoiceCommand command = new RegisterInvoiceCommand(
                    InvoiceNumber.of(text(json, "invoiceNumber")),
                    Iban.of(text(json, "creditorIban")),
                    text(json, "debtorName"),
                    Money.of(amount, currency),
                    PaymentReference.parse(optionalText(json, "reference")),
                    LocalDate.parse(text(json, "dueDate")));
            return new InvoiceCreatedMessage(text(json, "eventId"), command);
        } catch (InvalidInvoiceException | InvalidReferenceException | DateTimeParseException
                | IllegalArgumentException invalid) {
            throw new RejectedMessageException("The message has an invalid field: " + invalid.getMessage(), invalid);
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (!node.isString() || node.stringValue().isBlank()) {
            throw new RejectedMessageException("Field '" + field + "' is required and must be a string");
        }
        return node.stringValue();
    }

    private static String optionalText(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        return text(json, field);
    }
}
