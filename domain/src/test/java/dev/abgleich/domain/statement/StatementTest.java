package dev.abgleich.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class StatementTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    @Property
    void B11_statement_whose_balances_add_up_is_accepted(@ForAll("entries") List<StatementEntry> entries) {
        Money opening = Money.chf("500.00");

        Statement statement = statement(opening, closingFor(opening, entries), entries);

        assertThat(statement.entries()).hasSize(entries.size());
    }

    @Property
    void B11_one_cent_difference_rejects_the_whole_statement(@ForAll("entries") List<StatementEntry> entries) {
        Money opening = Money.chf("500.00");
        Money wrongClosing = closingFor(opening, entries).add(Money.chf("0.01"));

        assertThatThrownBy(() -> statement(opening, wrongClosing, entries))
                .isInstanceOf(InvalidStatementException.class)
                .extracting(e -> ((InvalidStatementException) e).reason())
                .isEqualTo(Reason.UNBALANCED);
    }

    @Test
    void B11_message_explains_the_difference() {
        List<StatementEntry> entries = List.of(entry("100.00", Direction.CREDIT), entry("12.00", Direction.DEBIT));

        assertThatThrownBy(() -> statement(Money.chf("0.00"), Money.chf("100.00"), entries))
                .hasMessage("Opening balance CHF 0.00 and 2 entries give CHF 88.00, but the closing balance is CHF 100.00");
    }

    @Test
    void B11_negative_balances_are_supported() {
        List<StatementEntry> entries = List.of(entry("50.00", Direction.DEBIT));

        Statement statement = statement(Money.chf("-10.00"), Money.chf("-60.00"), entries);

        assertThat(statement.closingBalance().amount()).isEqualTo(Money.chf("-60.00"));
    }

    @Test
    void B06_entry_in_another_currency_is_rejected() {
        List<StatementEntry> entries = List.of(new StatementEntry(
                Money.eur("10.00"), Direction.CREDIT, DAY, null, null, false, List.of()));

        assertThatThrownBy(() -> statement(Money.chf("0.00"), Money.chf("10.00"), entries))
                .isInstanceOf(InvalidStatementException.class)
                .extracting(e -> ((InvalidStatementException) e).reason())
                .isEqualTo(Reason.MIXED_CURRENCIES);
    }

    @Provide
    Arbitrary<List<StatementEntry>> entries() {
        Arbitrary<String> amounts = Arbitraries.bigDecimals()
                .between(new BigDecimal("0.01"), new BigDecimal("100000.00"))
                .ofScale(2)
                .map(BigDecimal::toPlainString);
        Arbitrary<Direction> directions = Arbitraries.of(Direction.class);
        return Combinators.combine(amounts, directions).as(StatementTest::entry).list().ofMaxSize(50);
    }

    private static Money closingFor(Money opening, List<StatementEntry> entries) {
        Money closing = opening;
        for (StatementEntry entry : entries) {
            closing = entry.direction() == Direction.CREDIT
                    ? closing.add(entry.amount())
                    : closing.subtract(entry.amount());
        }
        return closing;
    }

    private static Statement statement(Money opening, Money closing, List<StatementEntry> entries) {
        return new Statement(ACCOUNT, "STMT-1", new Balance(opening, DAY.minusDays(1)), new Balance(closing, DAY), entries);
    }

    private static StatementEntry entry(String amount, Direction direction) {
        return new StatementEntry(Money.chf(amount), direction, DAY, null, null, false, List.of());
    }
}
