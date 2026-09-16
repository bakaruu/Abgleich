package dev.abgleich.domain.archfixture;

/**
 * Deliberately wrong class, only used to prove that the B01 rule fails on doubles.
 * It lives in test sources, so production imports never see it.
 */
public class FloatingPointInvoice {

    private final double amount;

    public FloatingPointInvoice(double amount) {
        this.amount = amount;
    }

    public double amount() {
        return amount;
    }
}
