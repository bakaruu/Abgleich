package dev.abgleich.domain.statement;

/** Where a stored bank transaction stands in reconciliation. */
public enum TransactionStatus {
    UNMATCHED,
    PROPOSED,
    MATCHED,
    PARTIALLY_ALLOCATED,
    IGNORED
}
