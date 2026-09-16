package dev.abgleich.domain.matching;

/**
 * The matching rules, from the most to the least certain. Only {@link #R1} exists in phase F1; the
 * others arrive in F2 and can never confirm on their own (B27).
 */
public enum MatchRule {
    /** Structured reference (QRR or SCOR) and outstanding amount match exactly. */
    R1,
    /** Structured reference matches, amount differs (partial payment or overpayment). */
    R2,
    /** End-to-end id or bank reference matches a known payment. */
    R3,
    /** Invoice number found in the remittance text. */
    R4,
    /** Similar payer name and plausible amount. */
    R5,
    /** One payment for several invoices. */
    R6
}
