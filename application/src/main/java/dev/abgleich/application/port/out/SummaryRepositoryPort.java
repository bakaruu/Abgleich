package dev.abgleich.application.port.out;

import dev.abgleich.application.port.in.ReconciliationSummary;

/** Counts the stored payments, allocations, imports and events in one consistent read. */
public interface SummaryRepositoryPort {

    /** @throws StorageException if the database cannot be read */
    ReconciliationSummary summary();
}
