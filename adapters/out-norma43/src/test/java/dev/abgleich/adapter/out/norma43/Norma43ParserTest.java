package dev.abgleich.adapter.out.norma43;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.abgleich.application.port.out.ParsedStatementFile;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.application.port.out.StatementSniff;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.DeduplicationKey;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class Norma43ParserTest {

    private static final String FIXTURE = "/fixtures/norma43/spain-two-accounts-2026-09-15.n43";
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);

    private final Norma43Parser parser = new Norma43Parser();

    @Nested
    class RealisticSpanishFile {

        private final ParsedStatementFile file = parser.parse(new ByteArrayInputStream(fixtureBytes()));
        private final Statement taller = file.statements().getFirst();

        @Test
        void reads_every_account_of_the_file() {
            assertThat(file.format()).isEqualTo(StatementFormat.NORMA43);
            assertThat(file.messageId()).isNull();
            assertThat(file.statements()).extracting(Statement::account)
                    .containsExactly(Iban.of("ES9121000418450200051332"), Iban.of("ES5600491500080000012345"));
        }

        @Test
        void reads_balances_with_their_sign() {
            Statement madrid = file.statements().get(1);

            assertThat(taller.openingBalance().amount()).isEqualTo(Money.eur("5000.00"));
            assertThat(taller.closingBalance().amount()).isEqualTo(Money.eur("8263.50"));
            assertThat(madrid.openingBalance().amount()).as("sign 1 is a debit balance").isEqualTo(Money.eur("-120.00"));
            assertThat(madrid.closingBalance().amount()).isEqualTo(Money.eur("380.00"));
        }

        @Test
        void reads_a_transfer_with_its_concepts_and_reference() {
            StatementEntry transfer = taller.entries().getFirst();

            assertThat(transfer.direction()).isEqualTo(Direction.CREDIT);
            assertThat(transfer.bankReference()).as("Norma 43 has no unique bank reference").isNull();
            assertThat(transfer.details()).singleElement().satisfies(detail -> {
                assertThat(detail.amount()).isEqualTo(Money.eur("1815.00"));
                assertThat(detail.reference()).isEqualTo(new PaymentReference.FreeText("FV2026-0087"));
                assertThat(detail.remittanceText()).isEqualTo("TRANSF TALLERES RUIZ SL FRA 87 REF CLIENTE 4471");
                assertThat(detail.endToEndId()).as("reference 1 made of zeros").isNull();
            });
        }

        @Test
        void B10_commission_is_a_debit() {
            StatementEntry commission = taller.entries().get(4);

            assertThat(commission.direction()).isEqualTo(Direction.DEBIT);
            assertThat(commission.amount()).isEqualTo(Money.eur("3.50"));
            assertThat(commission.details().getFirst().bankReference()).as("document number").isEqualTo("0000004711");
        }

        @Test
        void B16_identical_lines_are_both_kept() {
            List<StatementEntry> entries = taller.entries();

            assertThat(entries.get(1)).isEqualTo(entries.get(2));
            assertThat(taller.deduplicationKeys()).hasSize(5).doesNotHaveDuplicates();
            assertThat(taller.deduplicationKeys().get(1).value()).endsWith(":1");
            assertThat(taller.deduplicationKeys().get(2).value()).endsWith(":2");
        }

        @Test
        void B16_reimporting_the_same_file_gives_the_same_keys() {
            Statement again = parser.parse(new ByteArrayInputStream(fixtureBytes())).statements().getFirst();

            assertThat(again.deduplicationKeys()).extracting(DeduplicationKey::value)
                    .isEqualTo(taller.deduplicationKeys().stream().map(DeduplicationKey::value).toList());
        }

        @Test
        void B17_two_digit_years_are_in_the_21st_century() {
            StatementEntry laterValue = taller.entries().get(3);

            assertThat(laterValue.bookingDate()).isEqualTo(SEP_15);
            assertThat(laterValue.valueDate()).isEqualTo(LocalDate.of(2026, 9, 16));
            assertThat(taller.openingBalance().date()).isEqualTo(SEP_15);
        }

        @Test
        void B18_amount_has_two_implied_decimals() {
            assertThat(taller.entries()).extracting(StatementEntry::amount).containsExactly(
                    Money.eur("1815.00"), Money.eur("605.00"), Money.eur("605.00"), Money.eur("242.00"),
                    Money.eur("3.50"));
        }

        @Test
        void B19_latin1_names_are_preserved() {
            TransactionDetail detail = taller.entries().get(3).details().getFirst();

            assertThat(detail.remittanceText()).isEqualTo("TRANSF JOSE MUÑOZ ÁLVAREZ FRA 90");
        }
    }

    @Test
    void B11_closing_balance_that_does_not_add_up_is_rejected() {
        List<String> lines = fixtureLines();
        lines.set(12, replace(lines.get(12), 60, 73, "00000000826351"));

        InvalidStatementException rejected = reject(lines);

        assertThat(rejected.reason()).isEqualTo(Reason.UNBALANCED);
        assertThat(rejected).hasMessage("Line 13: Opening balance EUR 5000.00 and 5 entries give EUR 8263.50,"
                + " but the closing balance is EUR 8263.51");
    }

    @Test
    void B11_truncated_file_is_rejected() {
        List<String> lines = fixtureLines().subList(0, 13);

        assertThat(reject(lines)).hasMessage("The file has no end record 88; it may be truncated");
    }

    @Test
    void B18_misread_amount_is_caught_by_the_totals_record() {
        List<String> lines = fixtureLines();
        lines.set(1, replace(lines.get(1), 29, 42, "00000000018150"));

        InvalidStatementException rejected = reject(lines);

        assertThat(rejected.reason()).isEqualTo(Reason.UNBALANCED);
        assertThat(rejected).hasMessageStartingWith("Line 13: Record 33 declares 1 debits of EUR 3.50 and 4 credits");
    }

    @Test
    void B19_file_read_with_the_wrong_charset_is_rejected_not_garbled() {
        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> new Norma43Parser(StandardCharsets.UTF_8).parse(new ByteArrayInputStream(fixtureBytes())));

        assertThat(rejected).isNotNull();
        assertThat(rejected).hasMessage("The Norma 43 file is not valid UTF-8 text");
    }

    @Test
    void B20_trimmed_lines_with_unix_line_endings_give_the_same_result() {
        String trimmedLf = String.join("\n", fixtureLines().stream().map(String::stripTrailing).toList()) + "\n";

        ParsedStatementFile trimmed = parser.parse(latin1(trimmedLf));

        ParsedStatementFile original = parser.parse(new ByteArrayInputStream(fixtureBytes()));
        assertThat(trimmed).isEqualTo(original);
    }

    @Test
    void B20_record_count_in_end_record_must_match() {
        List<String> lines = fixtureLines();
        lines.set(17, replace(lines.get(17), 21, 26, "000018"));

        assertThat(reject(lines)).hasMessage("Line 18: The end record 88 declares 18 records but the file has 17");
    }

    @Test
    void B20_totals_record_of_another_account_is_rejected() {
        List<String> lines = fixtureLines();
        lines.set(12, replace(lines.get(12), 11, 20, "0200051333"));

        assertThat(reject(lines)).hasMessage("Line 13: Record 33 belongs to a different account than its record 11");
    }

    @Test
    void B20_blank_lines_and_dos_end_of_file_marker_are_ignored() {
        String text = String.join("\r\n", fixtureLines()) + "\r\n\r\n";

        assertThat(parser.parse(latin1(text)).statements()).hasSize(2);
    }

    @Test
    void B20_data_after_the_end_record_is_rejected() {
        List<String> lines = fixtureLines();
        lines.add(lines.get(1));

        assertThat(reject(lines)).hasMessage("Line 19: There is data after the end record 88");
    }

    @Test
    void B39_invalid_fields_are_translated_into_a_domain_exception() {
        List<String> badAmount = fixtureLines();
        badAmount.set(1, replace(badAmount.get(1), 29, 42, "0000000018150A"));
        List<String> badDate = fixtureLines();
        badDate.set(1, replace(badDate.get(1), 11, 16, "260231"));

        assertThat(reject(badAmount)).hasMessage("Line 2: The movement amount (positions 29-42) must be numeric");
        assertThat(reject(badDate))
                .hasMessage("Line 2: The operation date (positions 11-16) is not a valid YYMMDD date")
                .hasCauseInstanceOf(InvalidStatementException.class);
    }

    @Test
    void B39_records_out_of_order_are_rejected() {
        List<String> conceptFirst = fixtureLines();
        conceptFirst.remove(1);
        List<String> unknownType = fixtureLines();
        unknownType.set(2, replace(unknownType.get(2), 1, 2, "29"));

        assertThat(reject(conceptFirst)).hasMessage("Line 2: Record 23 found without a movement record 22");
        assertThat(reject(unknownType)).hasMessage("Line 3: Unknown record type '29'");
    }

    @Test
    void B42_line_without_breaks_is_rejected_without_reading_it_all() {
        InputStream endless = new InputStream() {
            @Override
            public int read() {
                return '1';
            }
        };

        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> parser.parse(endless));

        assertThat(rejected.reason()).isEqualTo(Reason.FORBIDDEN_CONTENT);
        assertThat(rejected).hasMessage("Line 1 is longer than 256 characters");
    }

    @Test
    void format_is_detected_by_the_account_header() {
        byte[] camt = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.04">
                """.getBytes(StandardCharsets.UTF_8);

        assertThat(parser.canParse(StatementSniff.of(fixtureBytes()))).isTrue();
        assertThat(parser.canParse(StatementSniff.of(camt))).isFalse();
        assertThat(parser.canParse(StatementSniff.of("11 not really a header".getBytes(StandardCharsets.US_ASCII))))
                .isFalse();
    }

    private InvalidStatementException reject(List<String> lines) {
        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> parser.parse(latin1(String.join("\r\n", lines) + "\r\n")));
        assertThat(rejected).as("file should have been rejected").isNotNull();
        return rejected;
    }

    /** Replaces 1-based inclusive positions, as the AEB specification numbers them. */
    private static String replace(String record, int from, int to, String value) {
        assertThat(value).hasSize(to - from + 1);
        return record.substring(0, from - 1) + value + record.substring(to);
    }

    private static InputStream latin1(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static List<String> fixtureLines() {
        return new ArrayList<>(new String(fixtureBytes(), StandardCharsets.ISO_8859_1).lines().toList());
    }

    private static byte[] fixtureBytes() {
        try (InputStream in = Norma43ParserTest.class.getResourceAsStream(FIXTURE)) {
            assertThat(in).as("fixture").isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
