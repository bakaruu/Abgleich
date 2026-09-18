package dev.abgleich.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * B12: a camt.054 notification has no balances, so it can never be validated like a statement and never creates
 * transactions. What it must do is produce exactly the same deduplication keys a statement would, so both files
 * recognise the same payment.
 */
class NotificationTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final LocalDate BOOKED = LocalDate.of(2026, 9, 15);

    @Test
    void B06_a_notification_that_mixes_currencies_is_rejected() {
        assertThatThrownBy(() -> new Notification(ACCOUNT, "NTF-1", List.of(credit("100.00", "A"), euroCredit())))
                .isInstanceOf(InvalidStatementException.class)
                .hasMessageContaining("mixes currencies")
                .extracting(e -> ((InvalidStatementException) e).reason())
                .isEqualTo(Reason.MIXED_CURRENCIES);
    }

    @Test
    void one_currency_is_fine_however_many_entries_it_has() {
        Notification notification = new Notification(ACCOUNT, "NTF-2",
                List.of(credit("100.00", "A"), credit("200.00", "B"), credit("300.00", "C")));

        assertThat(notification.entries()).hasSize(3);
    }

    @Test
    void an_empty_notification_is_allowed_and_announces_nothing() {
        Notification empty = new Notification(ACCOUNT, "NTF-3", List.of());

        assertThat(empty.entries()).isEmpty();
        assertThat(empty.deduplicationKeys()).isEmpty();
    }

    @Test
    void B16_it_produces_one_key_per_entry_the_way_a_statement_does() {
        List<StatementEntry> entries = List.of(credit("100.00", "A"), credit("200.00", "B"));
        Notification notification = new Notification(ACCOUNT, "NTF-4", entries);

        assertThat(notification.deduplicationKeys())
                .hasSize(2)
                .doesNotHaveDuplicates()
                .isEqualTo(DeduplicationKey.forEntries(entries));
    }

    @Test
    void a_blank_id_is_the_same_as_no_id() {
        assertThat(new Notification(ACCOUNT, "  ", List.of()).notificationId()).isNull();
        assertThat(new Notification(ACCOUNT, null, List.of()).notificationId()).isNull();
        assertThat(new Notification(ACCOUNT, "NTF-5", List.of()).notificationId()).isEqualTo("NTF-5");
    }

    private static StatementEntry credit(String amount, String bankReference) {
        return new StatementEntry(Money.chf(amount), Direction.CREDIT, BOOKED, null, bankReference, false, List.of());
    }

    private static StatementEntry euroCredit() {
        return new StatementEntry(Money.eur("100.00"), Direction.CREDIT, BOOKED, null, "EUR-1", false, List.of());
    }
}
