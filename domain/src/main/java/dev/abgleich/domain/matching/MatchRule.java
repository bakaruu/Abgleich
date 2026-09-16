package dev.abgleich.domain.matching;

/**
 * The matching rules, from the most to the least certain, with the confidence each one carries.
 * Only {@link #R1} may confirm without a person (B27).
 */
public enum MatchRule {
    /** Structured reference (QRR or SCOR) and outstanding amount match exactly. */
    R1("1.00"),
    /** Structured reference matches, amount differs: partial payment, overpayment, bank charges, closed invoice. */
    R2("0.90"),
    /** Reference one character away from an invoice reference, amount matches exactly. */
    R3("0.85"),
    /** Invoice number found in the remittance text, amount matches exactly. */
    R4("0.80"),
    /** Similar payer name, exact or partial amount, booked close to the due date. */
    R5("0.70"),
    /** One payment for two to five invoices of the same debtor. */
    R6("0.75");

    private final Confidence confidence;

    MatchRule(String confidence) {
        this.confidence = Confidence.of(confidence);
    }

    public Confidence confidence() {
        return confidence;
    }
}
