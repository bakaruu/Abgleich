package dev.abgleich.application.service;

import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ImportStatementCommand;
import dev.abgleich.application.port.in.ImportStatementUseCase;
import dev.abgleich.application.port.in.ImportedStatement;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.in.ReconcileUseCase;
import dev.abgleich.application.port.in.StatementProcessed;
import java.util.Objects;

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
        ImportResult imported = importStatement.importStatement(command);
        return new StatementProcessed(imported, imported.statements().stream()
                .map(ImportedStatement::account)
                .map(reconcile::reconcilePending)
                .toList());
    }
}
