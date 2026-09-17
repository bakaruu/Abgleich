package dev.abgleich.adapter.out.camt;

import static dev.abgleich.adapter.out.camt.CamtXml.credit;
import static dev.abgleich.adapter.out.camt.CamtXml.debit;
import static dev.abgleich.adapter.out.camt.CamtXml.detail;
import static dev.abgleich.adapter.out.camt.CamtXml.document;
import static dev.abgleich.adapter.out.camt.CamtXml.statement;
import static dev.abgleich.adapter.out.camt.CamtXml.stream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.application.statement.port.out.ParsedStatementFile;
import dev.abgleich.application.statement.port.out.StatementSniff;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.reference.QrReference;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;
import javax.xml.stream.XMLStreamException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Camt053ParserTest {

    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);

    private final Camt053Parser parser = new Camt053Parser();

    @Nested
    class RealisticSwissStatement {

        private final ParsedStatementFile file = parseFixture("swiss-day-2026-09-15.xml");

        @Test
        void reads_file_level_data_and_every_statement() {
            assertThat(file.format()).isEqualTo(StatementFormat.CAMT053_V04);
            assertThat(file.messageId()).isEqualTo("ABG-CH-20260915-0001");
            assertThat(file.statements()).extracting(Statement::account)
                    .containsExactly(Iban.of("CH4431999123000889012"), Iban.of("CH9300762011623852957"));
        }

        @Test
        void reads_balances_as_booked_and_ignores_available_balances() {
            Statement qr = file.statements().getFirst();

            assertThat(qr.statementId()).isEqualTo("ABG-CH-20260915-QR");
            assertThat(qr.openingBalance().amount()).isEqualTo(Money.chf("10000.00"));
            assertThat(qr.openingBalance().date()).isEqualTo(LocalDate.of(2026, 9, 14));
            assertThat(qr.closingBalance().amount()).isEqualTo(Money.chf("14238.00"));
        }

        @Test
        void reads_a_qr_bill_payment() {
            StatementEntry payment = file.statements().getFirst().entries().getFirst();

            assertThat(payment.amount()).isEqualTo(Money.chf("1250.00"));
            assertThat(payment.direction()).isEqualTo(Direction.CREDIT);
            assertThat(payment.bookingDate()).isEqualTo(SEP_15);
            assertThat(payment.bankReference()).isEqualTo("BNK20260915000123");
            assertThat(payment.details()).singleElement().satisfies(detail -> {
                assertThat(detail.reference())
                        .isEqualTo(new PaymentReference.Qrr(QrReference.of("210000000003139471430009017")));
                assertThat(detail.counterpartyName()).isEqualTo("Muster Handwerk GmbH");
                assertThat(detail.endToEndId()).as("NOTPROVIDED means no id").isNull();
                assertThat(detail.bankReference()).isEqualTo("BNK20260915000123-1");
            });
        }

        @Test
        void B09_batch_booking_keeps_three_payments_inside_one_entry() {
            StatementEntry batch = file.statements().getFirst().entries().get(1);

            assertThat(batch.amount()).isEqualTo(Money.chf("3000.00"));
            assertThat(batch.valueDate()).isEqualTo(LocalDate.of(2026, 9, 16));
            assertThat(batch.details()).hasSize(3)
                    .extracting(TransactionDetail::amount).containsOnly(Money.chf("1000.00"));
            assertThat(batch.details()).extracting(TransactionDetail::reference)
                    .allMatch(PaymentReference.Qrr.class::isInstance);
        }

        @Test
        void B10_bank_fee_is_a_debit() {
            StatementEntry fee = file.statements().getFirst().entries().get(2);

            assertThat(fee.direction()).isEqualTo(Direction.DEBIT);
            assertThat(fee.direction().canPayInvoices()).isFalse();
            assertThat(fee.details()).isEmpty();
        }

        @Test
        void reads_creditor_reference_free_text_and_payments_without_reference() {
            Statement regular = file.statements().get(1);

            assertThat(regular.entries()).extracting(entry -> entry.details().getFirst().reference())
                    .containsExactly(
                            new PaymentReference.Scor(CreditorReference.of("RF18539007547034")),
                            PaymentReference.none(),
                            PaymentReference.none());
            TransactionDetail freeText = regular.entries().get(1).details().getFirst();
            assertThat(freeText.remittanceText()).isEqualTo("Rechnung 143 vielen Dank");
            assertThat(freeText.counterpartyName()).as("UTF-8 umlauts survive").isEqualTo("Zürcher Bäckerei Löwen");
            assertThat(regular.entries().get(2).details().getFirst().counterpartyName()).isEqualTo("Brunner & Co. AG");
        }

        @Test
        void pending_entries_are_not_imported() {
            assertThat(file.statements().get(1).entries()).hasSize(3)
                    .extracting(StatementEntry::amount).doesNotContain(Money.chf("99.00"));
        }
    }

    @Test
    void B08_external_entity_cannot_read_server_files(@TempDir Path dir) throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-SERVER-FILE");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE Document [<!ENTITY xxe SYSTEM "%s">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.04">
                  <BkToCstmrStmt><GrpHdr><MsgId>&xxe;</MsgId></GrpHdr></BkToCstmrStmt>
                </Document>
                """.formatted(secret.toUri());

        InvalidStatementException rejected = reject(xml);

        assertThat(rejected.reason()).isEqualTo(Reason.FORBIDDEN_CONTENT);
        assertThat(messagesOf(rejected)).noneMatch(message -> message.contains("TOP-SECRET"));
    }

    @Test
    void B08_entity_expansion_bomb_is_rejected() {
        String xml = """
                <?xml version="1.0"?>
                <!DOCTYPE Document [
                  <!ENTITY a "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa">
                  <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
                  <!ENTITY c "&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;&b;">
                ]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.04">&c;</Document>
                """;

        assertThat(reject(xml).reason()).isEqualTo(Reason.FORBIDDEN_CONTENT);
    }

    @Test
    void B09_batch_details_that_do_not_add_up_are_rejected() {
        String xml = document(statement().entry(credit("3000.00")
                .detail(detail().amount("1000.00"))
                .detail(detail().amount("1000.00"))
                .detail(detail().amount("900.00"))));

        InvalidStatementException rejected = reject(xml);

        assertThat(rejected.reason()).isEqualTo(Reason.INCONSISTENT_ENTRY);
        assertThat(rejected).hasMessageStartingWith("Entry 1: Transactions add up to CHF 2900.00");
    }

    @Test
    void B10_entry_without_credit_debit_indicator_is_rejected() {
        String xml = document(statement().entry(CamtXml.withoutIndicator("50.00")));

        assertThat(reject(xml)).hasMessage("Entry 1: The entry has no credit/debit indicator (CdtDbtInd)");
    }

    @Test
    void B10_counterparty_is_the_payee_of_a_debit() {
        String xml = document(statement().opening("100.00")
                .entry(debit("40.00").detail(detail().debtor("Our Company").creditor("Supplier GmbH"))));

        TransactionDetail detail = parse(xml).statements().getFirst().entries().getFirst().details().getFirst();

        assertThat(detail.counterpartyName()).isEqualTo("Supplier GmbH");
    }

    @Test
    void B10_reversal_indicator_is_kept() {
        String xml = document(statement().opening("500.00").entry(debit("480.00").reversal()));

        StatementEntry reversal = parse(xml).statements().getFirst().entries().getFirst();

        assertThat(reversal.reversal()).isTrue();
        assertThat(reversal.direction()).isEqualTo(Direction.DEBIT);
    }

    @Test
    void B11_unbalanced_statement_is_rejected() {
        String xml = document(statement().opening("100.00").closing("1350.00")
                .entry(credit("1250.00"))
                .entry(debit("12.00")));

        InvalidStatementException rejected = reject(xml);

        assertThat(rejected.reason()).isEqualTo(Reason.UNBALANCED);
        assertThat(rejected).hasMessage(
                "Opening balance CHF 100.00 and 2 entries give CHF 1338.00, but the closing balance is CHF 1350.00");
    }

    @Test
    void B11_negative_balances_use_the_debit_indicator() {
        String xml = document(statement().opening("-200.00").entry(credit("50.00")));

        Statement parsed = parse(xml).statements().getFirst();

        assertThat(parsed.openingBalance().amount()).isEqualTo(Money.chf("-200.00"));
        assertThat(parsed.closingBalance().amount()).isEqualTo(Money.chf("-150.00"));
    }

    @Test
    void B11_truncated_file_is_rejected() {
        String complete = document(statement().entry(credit("10.00")).entry(credit("20.00")));
        String truncated = complete.substring(0, complete.lastIndexOf("<Ntry>") + 20);

        InvalidStatementException rejected = reject(truncated);

        assertThat(rejected.reason()).isEqualTo(Reason.MALFORMED_FILE);
    }

    @Test
    void B11_statement_without_closing_balance_is_rejected() {
        String xml = document(statement()).replaceAll("<Bal><Tp><CdOrPrtry><Cd>CLBD.*?</Bal>", "");

        assertThat(reject(xml)).hasMessageContaining("closing (CLBD) balance");
    }

    @Test
    void B13_unsupported_camt_version_is_rejected_explicitly() {
        String xml = document("urn:iso:std:iso:20022:tech:xsd:camt.053.001.02", statement());

        InvalidStatementException rejected = reject(xml);

        assertThat(rejected.reason()).isEqualTo(Reason.UNSUPPORTED_VERSION);
        assertThat(rejected).hasMessage("camt.053 version 001.02 is not supported; supported versions: 001.04, 001.08");
    }

    @Test
    void B13_version_08_is_read_with_its_own_element_paths() {
        ParsedStatementFile v04 = parseFixture("swiss-day-2026-09-15.xml");
        ParsedStatementFile v08 = parseFixture("swiss-day-2026-09-15-v08.xml");

        assertThat(v08.format()).isEqualTo(StatementFormat.CAMT053_V08);
        assertThat(v08.statements()).as("status codes, transaction amounts and party names read from v08 paths")
                .isEqualTo(v04.statements());
    }

    @Test
    void B07_charges_reported_for_a_payment_are_read() {
        String xml = document(statement().entry(credit("472.50").detail(detail().amount("472.50")))).replace(
                "</AmtDtls>", "</AmtDtls><Chrgs><Rcrd><Amt Ccy=\"CHF\">5.00</Amt></Rcrd><Rcrd><Amt Ccy=\"CHF\">2.50</Amt></Rcrd></Chrgs>");

        TransactionDetail detail = parse(xml).statements().getFirst().entries().getFirst().details().getFirst();

        assertThat(detail.charges()).isEqualTo(Money.chf("7.50"));
    }

    @Test
    void B13_other_xml_documents_are_rejected() {
        String camt054 = document("urn:iso:std:iso:20022:tech:xsd:camt.054.001.04", statement());

        assertThat(reject(camt054)).hasMessage("The file is not a camt.053 document");
    }

    @Test
    void B13_format_is_detected_by_content_for_every_camt053_version() {
        byte[] v04 = document(statement()).getBytes(StandardCharsets.UTF_8);
        byte[] v08 = document("urn:iso:std:iso:20022:tech:xsd:camt.053.001.08", statement())
                .getBytes(StandardCharsets.UTF_8);
        byte[] norma43 = "11210004180000123456250915250915100000000000000000978TALLERES RUIZ SL      3"
                .getBytes(StandardCharsets.ISO_8859_1);

        assertThat(parser.canParse(StatementSniff.of(v04))).isTrue();
        assertThat(parser.canParse(StatementSniff.of(v08))).as("rejected later with a clear message").isTrue();
        assertThat(parser.canParse(StatementSniff.of(norma43))).isFalse();
    }

    @Test
    void B14_large_statement_is_streamed() {
        int entries = 50_000;

        ParsedStatementFile file = parser.parse(CamtXml.largeStatement(entries));

        Statement statement = file.statements().getFirst();
        assertThat(statement.entries()).hasSize(entries);
        assertThat(statement.closingBalance().amount()).isEqualTo(Money.chf("50000.00"));
    }

    @Test
    void B15_date_time_keeps_the_calendar_date_the_bank_wrote() {
        String xml = document(statement().entry(credit("10.00")
                .bookingDateTime("2026-09-15T00:30:00+02:00")
                .valueDateTime("2026-09-16T01:15:00.000+02:00")));

        StatementEntry entry = parse(xml).statements().getFirst().entries().getFirst();

        assertThat(entry.bookingDate()).as("in UTC this is 22:30 on the 14th").isEqualTo(SEP_15);
        assertThat(entry.valueDate()).as("in UTC this is 23:15 on the 15th").isEqualTo(LocalDate.of(2026, 9, 16));
    }

    @Test
    void B39_xml_errors_are_translated_into_a_domain_exception() {
        InvalidStatementException rejected = reject("<Document xmlns=\"" + Camt053Parser.NAMESPACE_V04 + "\"><Unclosed>");

        assertThat(rejected.reason()).isEqualTo(Reason.MALFORMED_FILE);
        assertThat(rejected).hasCauseInstanceOf(XMLStreamException.class);
    }

    @Test
    void B39_invalid_values_are_translated_into_a_domain_exception() {
        String commaDecimal = document(statement().opening("100.00").entry(credit("12.50"))).replace(">12.50<", ">12,50<");
        String badIban = document(statement()).replace(CamtXml.IBAN, "CH0000762011623852957");

        assertThat(reject(commaDecimal)).hasMessage("Entry 1: The entry amount is not a valid amount in a supported currency");
        assertThat(reject(badIban)).hasMessage("The account IBAN is invalid");
    }

    @Test
    void B39_reference_longer_than_the_iso_limit_is_rejected() {
        String xml = document(statement().entry(credit("10.00").detail(detail().reference("X".repeat(36)))));

        assertThat(reject(xml)).hasMessage("Entry 1: The creditor reference is longer than 35 characters");
    }

    @Test
    void B42_deeply_nested_elements_are_rejected() {
        String deep = "<a>".repeat(100) + "</a>".repeat(100);
        String xml = document(statement()).replace("<GrpHdr>", "<GrpHdr>" + deep);

        InvalidStatementException rejected = reject(xml);

        assertThat(rejected.reason()).isEqualTo(Reason.FORBIDDEN_CONTENT);
        assertThat(rejected).hasMessageContaining("nested deeper than 32 levels");
    }

    @Test
    void B42_oversized_text_is_rejected() {
        String xml = document(statement()).replace("MSG-1", "x".repeat(10_000));

        assertThat(reject(xml).reason()).isEqualTo(Reason.FORBIDDEN_CONTENT);
    }

    private ParsedStatementFile parse(String xml) {
        return parser.parse(stream(xml));
    }

    private InvalidStatementException reject(String xml) {
        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class, () -> parse(xml));
        assertThat(rejected).as("file should have been rejected").isNotNull();
        return rejected;
    }

    private ParsedStatementFile parseFixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/camt053/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return parser.parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<String> messagesOf(Throwable error) {
        return Stream.iterate(error, e -> e != null, Throwable::getCause).map(String::valueOf);
    }
}
