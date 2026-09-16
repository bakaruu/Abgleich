package dev.abgleich.domain.statement;

/** Where a stored bank transaction stands in reconciliation. */
public enum TransactionStatus {
    UNMATCHED,
    PROPOSED,
    MATCHED,
    PARTIALLY_ALLOCATED,
    IGNORED,
    /** A matched credit the bank later reversed; its allocations are reversed too (B10). */
    REVERSED
}
