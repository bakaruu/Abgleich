package dev.abgleich.application.port.in;

import dev.abgleich.domain.account.Iban;

/**
 * Matches the unmatched credits of an account against its open invoices. Runs after an import has
 * committed, one short transaction per payment, so a long matching run never blocks imports (B26).
 * Running it again is harmless: decided payments are no longer unmatched.
 */
public interface ReconcileUseCase {

    ReconciliationRun reconcilePending(Iban account);
}
