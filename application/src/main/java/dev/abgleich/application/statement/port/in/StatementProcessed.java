package dev.abgleich.application.statement.port.in;

import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import java.util.List;
import java.util.Objects;

/**
 * @param reconciliations one run per account of the file: first one per imported statement, then one per
 *     enriched notification, in the same order as in {@code imported}
 */
public record StatementProcessed(ImportResult imported, List<ReconciliationRun> reconciliations) {

    public StatementProcessed {
        Objects.requireNonNull(imported, "imported");
        reconciliations = List.copyOf(reconciliations);
        if (reconciliations.size() != imported.statements().size() + imported.enriched().size()) {
            throw new IllegalArgumentException("One reconciliation run per account of the file is required");
        }
    }
}
