package dev.abgleich.application.statement;

import dev.abgleich.domain.account.Iban;
import java.util.Objects;

/**
 * What a camt.054 added for one account (B12).
 *
 * @param enriched transactions that received details they did not have
 * @param alreadyComplete transactions found but with nothing new to add
 * @param unknown notified entries with no stored transaction yet: import the day's statement first
 */
public record EnrichedNotification(Iban account, int enriched, int alreadyComplete, int unknown) {

    public EnrichedNotification {
        Objects.requireNonNull(account, "account");
    }
}
