package dev.abgleich.adapter.out.camt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.application.statement.port.out.ParsedStatementFile;
import dev.abgleich.application.statement.port.out.StatementSniff;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.Notification;
import dev.abgleich.domain.statement.TransactionDetail;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Camt054ParserTest {

    private final Camt054Parser parser = new Camt054Parser();

    @Test
    void B12_notification_is_read_without_balances_and_with_the_statement_keys() {
        ParsedStatementFile file = parser.parse(fixture("/fixtures/camt054/swiss-notification-2026-09-15.xml"));

        assertThat(file.format()).isEqualTo(StatementFormat.CAMT054_V08);
        assertThat(file.isNotification()).isTrue();
        assertThat(file.statements()).isEmpty();
        Notification notification = file.notifications().getFirst();
        assertThat(notification.account()).isEqualTo(Iban.of("CH9300762011623852957"));
        assertThat(notification.deduplicationKeys().getFirst().value()).isEqualTo("BANK:BNK20260915000203");
        TransactionDetail detail = notification.entries().getFirst().details().getFirst();
        assertThat(detail.remittanceText()).isEqualTo("Teilzahlung Rechnung F-2026-0144");
        assertThat(detail.counterpartyName()).isEqualTo("Brunner & Co. AG");
        assertThat(detail.charges()).isEqualTo(Money.chf("0.00"));
    }

    @Test
    void B13_each_parser_claims_only_its_own_message() {
        byte[] notification = bytes("/fixtures/camt054/swiss-notification-2026-09-15.xml");
        byte[] statement = bytes("/fixtures/camt053/swiss-day-2026-09-15.xml");

        assertThat(parser.canParse(StatementSniff.of(notification))).isTrue();
        assertThat(parser.canParse(StatementSniff.of(statement))).isFalse();
        assertThat(new Camt053Parser().canParse(StatementSniff.of(notification))).isFalse();
    }

    @Test
    void B13_a_statement_given_to_the_notification_parser_is_rejected() {
        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> parser.parse(fixture("/fixtures/camt053/swiss-day-2026-09-15.xml")));

        assertThat(rejected).hasMessage("The file is not a camt.054 document");
    }

    @Test
    void B13_unknown_notification_version_is_rejected() {
        String xml = new String(bytes("/fixtures/camt054/swiss-notification-2026-09-15.xml"), StandardCharsets.UTF_8)
                .replace("camt.054.001.08", "camt.054.001.09");

        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> parser.parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))));

        assertThat(rejected).hasMessage("camt.054 version 001.09 is not supported; supported versions: 001.04, 001.08");
    }

    private static InputStream fixture(String name) {
        return new java.io.ByteArrayInputStream(bytes(name));
    }

    private static byte[] bytes(String name) {
        try (InputStream in = Camt054ParserTest.class.getResourceAsStream(name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
