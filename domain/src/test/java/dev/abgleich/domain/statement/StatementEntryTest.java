package dev.abgleich.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class StatementEntryTest {

    private static final LocalDate BOOKED = LocalDate.of(2026, 9, 15);

    @Test
    void B09_batch_entry_keeps_the_entry_amount_once() {
        StatementEntry batch = credit("3000.00", detail("1000.00"), detail("1000.00"), detail("1000.00"));

        assertThat(batch.amount()).isEqualTo(Money.chf("3000.00"));
        assertThat(batch.details()).extracting(TransactionDetail::amount).containsOnly(Money.chf("1000.00"));
    }

    @Test
    void B09_batch_details_that_do_not_add_up_are_rejected() {
        assertThatThrownBy(() -> credit("3000.00", detail("1000.00"), detail("1000.00"), detail("900.00")))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessage("Transactions add up to CHF 2900.00 but the entry amount is CHF 3000.00")
                .extracting(e -> ((InvalidStatementException) e).reason())
                .isEqualTo(Reason.INCONSISTENT_ENTRY);
    }

    @Test
    void B09_batch_detail_without_amount_is_rejected() {
        assertThatThrownBy(() -> credit("3000.00", detail("1000.00"), detail(null)))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessageContaining("must state the amount of each one");
    }

    @Test
    void single_detail_without_amount_takes_the_entry_amount() {
        StatementEntry entry = credit("1250.00", detail(null));

        assertThat(entry.details()).singleElement()
                .extracting(TransactionDetail::amount).isEqualTo(Money.chf("1250.00"));
    }

    @Test
    void B10_amount_is_never_negative_the_direction_carries_the_sign() {
        assertThatThrownBy(() -> new StatementEntry(
                Money.chf("-12.00"), Direction.DEBIT, BOOKED, null, null, false, List.of()))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessageContaining("direction carries the sign");
    }

    @Test
    void B06_detail_in_another_currency_is_rejected() {
        assertThatThrownBy(() -> new StatementEntry(Money.chf("100.00"), Direction.CREDIT, BOOKED, null, null, false,
                List.of(new TransactionDetail(Money.eur("100.00"), null, null, null, null, null))))
                .isInstanceOf(InvalidStatementException.class)
                .extracting(e -> ((InvalidStatementException) e).reason())
                .isEqualTo(Reason.MIXED_CURRENCIES);
    }

    @Test
    void value_date_defaults_to_booking_date() {
        assertThat(credit("10.00").valueDate()).isEqualTo(BOOKED);
    }

    @Test
    void B41_detail_to_string_hides_names_and_remittance_text() {
        TransactionDetail detail = new TransactionDetail(Money.chf("1200.00"), PaymentReference.none(),
                "Rechnung 144 Brunner", "Brunner & Co. AG", null, null);

        assertThat(detail.toString()).doesNotContain("Brunner").contains("CHF 1200.00");
    }

    private static StatementEntry credit(String amount, TransactionDetail... details) {
        return new StatementEntry(Money.chf(amount), Direction.CREDIT, BOOKED, null, null, false, List.of(details));
    }

    private static TransactionDetail detail(String amount) {
        return new TransactionDetail(amount == null ? null : Money.chf(amount), null, null, null, null, null);
    }
}
