package dev.abgleich.domain.statement;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.util.List;
import java.util.Objects;

/**
 * Entries a bank notifies during the day (camt.054). A notification has no balances, so it cannot be
 * validated like a statement (B11) and never creates transactions: it only adds details to entries a
 * statement already stored (B12).
 */
public record Notification(Iban account, String notificationId, List<StatementEntry> entries) {

    public Notification {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(entries, "entries");
        notificationId = Texts.blankToNull(notificationId);
        entries = List.copyOf(entries);
        if (entries.stream().map(entry -> entry.amount().currency()).distinct().count() > 1) {
            throw new InvalidStatementException(Reason.MIXED_CURRENCIES, "A notification mixes currencies");
        }
    }

    /** One key per entry, computed exactly like a statement's, so both files find the same transaction. */
    public List<DeduplicationKey> deduplicationKeys() {
        return DeduplicationKey.forEntries(entries);
    }
}
