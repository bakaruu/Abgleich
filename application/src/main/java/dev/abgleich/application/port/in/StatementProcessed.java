package dev.abgleich.application.port.in;

import java.util.List;
import java.util.Objects;

/** @param reconciliations one run per statement, in the same order as {@code imported.statements()} */
public record StatementProcessed(ImportResult imported, List<ReconciliationRun> reconciliations) {

    public StatementProcessed {
        Objects.requireNonNull(imported, "imported");
        reconciliations = List.copyOf(reconciliations);
        if (reconciliations.size() != imported.statements().size()) {
            throw new IllegalArgumentException("One reconciliation run per imported statement is required");
        }
    }
}
