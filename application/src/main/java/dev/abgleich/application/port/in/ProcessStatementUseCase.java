package dev.abgleich.application.port.in;

import dev.abgleich.domain.statement.InvalidStatementException;

/**
 * What a user or an integration means by "upload a statement": import it, then reconcile every
 * account it contains. The two steps stay separate transactions (B26); reconciliation also runs for
 * a file imported before, so invoices registered since then are picked up.
 */
public interface ProcessStatementUseCase {

    /** @throws InvalidStatementException if the file is rejected; nothing is stored or reconciled */
    StatementProcessed process(ImportStatementCommand command);
}
