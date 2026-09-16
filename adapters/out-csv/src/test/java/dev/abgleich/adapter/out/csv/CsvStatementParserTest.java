package dev.abgleich.adapter.out.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.abgleich.application.port.out.ParsedStatementFile;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CsvStatementParserTest {

    private static final String TEXT = new String(CsvStatementParserContractTest.FIXTURE, StandardCharsets.UTF_8);

    private final CsvStatementParser parser = new CsvStatementParser();

    @Test
    void reads_account_balances_and_entries() {
        Statement statement = parse(TEXT).statements().getFirst();

        assertThat(statement.account()).isEqualTo(Iban.of("CH9300762011623852957"));
        assertThat(statement.closingBalance().amount()).isEqualTo(Money.chf("4648.00"));
        assertThat(statement.entries()).hasSize(4);
        StatementEntry scor = statement.entries().getFirst();
        assertThat(scor.bankReference()).isEqualTo("CSV-0001");
        assertThat(scor.details().getFirst().reference()).isInstanceOf(PaymentReference.Scor.class);
        assertThat(statement.entries().get(3).direction()).isEqualTo(Direction.DEBIT);
    }

    @Test
    void quoted_fields_keep_delimiters_and_B19_utf8_names() {
        TransactionDetail text = parse(TEXT).statements().getFirst().entries().get(1).details().getFirst();

        assertThat(text.remittanceText()).isEqualTo("Rechnung 143; vielen Dank");
        assertThat(text.counterpartyName()).isEqualTo("Zürcher Bäckerei Löwen");
    }

    @Test
    void B07_charges_column_is_read() {
        StatementEntry entry = parse(TEXT).statements().getFirst().entries().get(2);

        assertThat(entry.valueDate()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(entry.details().getFirst().charges()).isEqualTo(Money.chf("7.50"));
    }

    @Test
    void B19_byte_order_mark_is_accepted_and_invalid_utf8_is_rejected() {
        byte[] bom = ("﻿" + TEXT).getBytes(StandardCharsets.UTF_8);
        byte[] latin1 = TEXT.getBytes(StandardCharsets.ISO_8859_1);

        assertThat(parser.parse(new ByteArrayInputStream(bom)).statements()).hasSize(1);
        assertThat(reject(latin1)).hasMessage("The CSV file is not valid UTF-8 text");
    }

    @Test
    void B18_amounts_with_a_comma_are_rejected_not_misread() {
        String commaDecimal = TEXT.replace(";1200.00;", ";1.200,00;");

        assertThat(reject(commaDecimal.getBytes(StandardCharsets.UTF_8)))
                .hasMessage("Line 5: amount must be a positive number with a dot and at most two decimals, like 1815.00");
    }

    @Test
    void B11_rows_that_do_not_add_up_to_the_closing_balance_are_rejected() {
        String missingRow = TEXT.replaceFirst("2026-09-15;2026-09-15;DEBIT;12.00;[^\r\n]*\r\n", "");

        assertThat(reject(missingRow.getBytes(StandardCharsets.UTF_8)).reason()).isEqualTo(Reason.UNBALANCED);
    }

    @Test
    void B13_unknown_version_and_columns_are_rejected() {
        assertThat(reject(TEXT.replace("version=1", "version=2").getBytes(StandardCharsets.UTF_8)).reason())
                .isEqualTo(Reason.UNSUPPORTED_VERSION);
        assertThat(reject(TEXT.replace(";charges\r\n", ";fee\r\n").getBytes(StandardCharsets.UTF_8)))
                .hasMessageStartingWith("Line 2: the columns must be exactly");
    }

    @Test
    void B42_oversized_field_is_rejected() {
        String huge = TEXT.replace("Kontoführungsgebühr", "x".repeat(501));

        assertThat(reject(huge.getBytes(StandardCharsets.UTF_8)).reason()).isEqualTo(Reason.FORBIDDEN_CONTENT);
    }

    @Test
    void B39_unclosed_quote_is_a_domain_exception() {
        String unclosed = TEXT.replace("\"Brunner & Co. AG\"", "\"Brunner & Co. AG");

        assertThat(reject(unclosed.getBytes(StandardCharsets.UTF_8)).reason()).isIn(Reason.MALFORMED_FILE, Reason.UNBALANCED);
    }

    private ParsedStatementFile parse(String text) {
        return parser.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private InvalidStatementException reject(byte[] bytes) {
        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> parser.parse(new ByteArrayInputStream(bytes)));
        assertThat(rejected).as("file should have been rejected").isNotNull();
        return rejected;
    }
}
