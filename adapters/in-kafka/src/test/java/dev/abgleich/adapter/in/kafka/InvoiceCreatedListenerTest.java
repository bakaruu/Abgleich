package dev.abgleich.adapter.in.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.invoice.InvoiceReceipt;
import dev.abgleich.application.invoice.MessageChannel;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

class InvoiceCreatedListenerTest {

    private static final String VALID = """
            {"specVersion": 1, "eventId": "erp-evt-0001", "invoiceNumber": " f-2026-0142 ",
             "creditorIban": "CH93 0076 2011 6238 5295 7", "debtorName": "Keller GmbH", "amount": "1250.00",
             "currency": "chf", "reference": "RF18 5390 0754 7034", "dueDate": "2026-09-30"}
            """;

    private final List<RegisterInvoiceCommand> received = new ArrayList<>();
    private final List<String> messageIds = new ArrayList<>();
    private final InvoiceCreatedListener listener = new InvoiceCreatedListener((channel, id, command) -> {
        assertThat(channel).isEqualTo(MessageChannel.KAFKA);
        messageIds.add(id);
        received.add(command);
        return new InvoiceReceipt(InvoiceReceipt.Outcome.REGISTERED, UUID.randomUUID(), false);
    });

    @Test
    void B05_valid_event_becomes_a_command_with_normalized_value_objects() {
        listener.onMessage(record(VALID));

        assertThat(messageIds).containsExactly("erp-evt-0001");
        assertThat(received).singleElement().satisfies(command -> {
            assertThat(command.number()).isEqualTo(InvoiceNumber.of("F-2026-0142"));
            assertThat(command.creditorAccount()).isEqualTo(Iban.of("CH9300762011623852957"));
            assertThat(command.amount()).isEqualTo(Money.chf("1250.00"));
            assertThat(command.reference()).isEqualTo(PaymentReference.parse("RF18539007547034"));
            assertThat(command.dueDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        });
    }

    @Test
    void B01_amount_as_json_number_is_rejected() {
        assertRejected(VALID.replace("\"1250.00\"", "1250.00"), "amount");
        assertRejected(VALID.replace("\"1250.00\"", "\"1250.005\""), "amount");
    }

    @Test
    void messages_that_can_never_be_processed_are_rejected_not_retried() {
        assertRejected("not json", "not valid JSON");
        assertRejected("[1, 2]", "not a JSON object");
        assertRejected(VALID.replace("\"specVersion\": 1", "\"specVersion\": 2"), "specVersion");
        assertRejected(VALID.replace("\"invoiceNumber\": \" f-2026-0142 \",", ""), "invoiceNumber");
        assertRejected(VALID.replace("CH93 0076 2011 6238 5295 7", "CH00 0000"), "invalid field");
        assertRejected(VALID.replace("2026-09-30", "30.09.2026"), "invalid field");
        assertRejected(VALID.replace("chf", "XYZ"), "invalid field");
        assertRejected("{\"x\": \"" + "a".repeat(InvoiceCreatedMessage.MAX_BYTES) + "\"}", "larger than");
        assertThat(received).isEmpty();
    }

    @Test
    void invalid_invoice_or_event_id_rejected_by_the_use_case_is_not_retried() {
        InvoiceCreatedListener refusing = new InvoiceCreatedListener((channel, id, command) -> {
            throw new IllegalArgumentException("A message id has 1 to 64 letters");
        });

        assertThatThrownBy(() -> refusing.onMessage(record(VALID))).isInstanceOf(RejectedMessageException.class);
    }

    private void assertRejected(String value, String message) {
        assertThatThrownBy(() -> listener.onMessage(record(value)))
                .isInstanceOf(RejectedMessageException.class)
                .hasMessageContaining(message);
    }

    private static ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("abgleich.invoices.created", 0, 0, "key", value);
    }
}
