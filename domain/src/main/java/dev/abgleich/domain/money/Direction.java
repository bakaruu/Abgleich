package dev.abgleich.domain.money;

/**
 * Whether money entered or left the account. Bank files carry unsigned amounts,
 * so the direction is always explicit (B10): CRDT/DBIT in camt, 2/1 in Norma 43.
 */
public enum Direction {
    CREDIT,
    DEBIT;

    public boolean canPayInvoices() {
        return this == CREDIT;
    }
}
