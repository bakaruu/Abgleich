package dev.abgleich.application.statement.service;

import dev.abgleich.application.reconciliation.port.in.ReconcileUseCase;
import dev.abgleich.application.statement.EnrichedNotification;
import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.application.statement.port.in.ImportResult;
import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ImportStatementUseCase;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import java.util.Objects;
import java.util.stream.Stream;

public final class ProcessStatementService implements ProcessStatementUseCase {

    private final ImportStatementUseCase importStatement;
    private final ReconcileUseCase reconcile;

    public ProcessStatementService(ImportStatementUseCase importStatement, ReconcileUseCase reconcile) {
        this.importStatement = Objects.requireNonNull(importStatement, "importStatement");
        this.reconcile = Objects.requireNonNull(reconcile, "reconcile");
    }

    @Override
    public StatementProcessed process(ImportStatementCommand command) {
        // The import has committed when it returns; matching starts only afterwards (B26).
        // Enriched details (a remittance text, a payer name) can make a pending payment matchable.
        ImportResult imported = importStatement.importStatement(command);
        return new StatementProcessed(imported, Stream.concat(
                        imported.statements().stream().map(ImportedStatement::account),
                        imported.enriched().stream().map(EnrichedNotification::account))
                .map(reconcile::reconcilePending)
                .toList());
    }
}
