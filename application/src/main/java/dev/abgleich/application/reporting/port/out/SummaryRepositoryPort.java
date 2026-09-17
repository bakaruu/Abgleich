package dev.abgleich.application.reporting.port.out;

import dev.abgleich.application.reporting.ReconciliationSummary;

/** Counts the stored payments, allocations, imports and events in one consistent read. */
public interface SummaryRepositoryPort {

    /** @throws StorageException if the database cannot be read */
    ReconciliationSummary summary();
}
