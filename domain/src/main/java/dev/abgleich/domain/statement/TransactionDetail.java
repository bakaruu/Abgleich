package dev.abgleich.domain.statement;

import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.util.Objects;

/**
 * One payment inside a bank entry. A batch entry carries several details (B09).
 *
 * <p>{@code amount} may be {@code null} only while building an entry with a single detail; the
 * entry then fills it in. Optional text fields are {@code null} when absent, never blank.
 * {@link #toString()} leaves out names and remittance text, which are personal data (B41).
 *
 * @param charges bank charges deducted from this payment as reported by the bank, or {@code null} (B07)
 */
public record TransactionDetail(
        Money amount,
        PaymentReference reference,
        String remittanceText,
        String counterpartyName,
        String endToEndId,
        String bankReference,
        Money charges) {

    public TransactionDetail {
        if (amount != null && !amount.isPositive()) {
            throw new InvalidStatementException(
                    InvalidStatementException.Reason.INCONSISTENT_ENTRY, "Transaction amount must be positive");
        }
        if (charges != null && (charges.isNegative() || (amount != null && !charges.hasSameCurrencyAs(amount)))) {
            throw new InvalidStatementException(
                    InvalidStatementException.Reason.MIXED_CURRENCIES, "Charges must be zero or positive in the payment currency");
        }
        reference = Objects.requireNonNullElseGet(reference, PaymentReference::none);
        remittanceText = Texts.blankToNull(remittanceText);
        counterpartyName = Texts.blankToNull(counterpartyName);
        endToEndId = Texts.blankToNull(endToEndId);
        bankReference = Texts.blankToNull(bankReference);
    }

    /** A detail without charges information, as most files provide. */
    public TransactionDetail(Money amount, PaymentReference reference, String remittanceText, String counterpartyName,
            String endToEndId, String bankReference) {
        this(amount, reference, remittanceText, counterpartyName, endToEndId, bankReference, null);
    }

    TransactionDetail withAmount(Money newAmount) {
        return new TransactionDetail(newAmount, reference, remittanceText, counterpartyName, endToEndId, bankReference,
                charges);
    }

    @Override
    public String toString() {
        return "TransactionDetail[amount=" + amount
                + ", reference=" + reference.getClass().getSimpleName()
                + ", remittanceText=" + (remittanceText == null ? "none" : remittanceText.length() + " chars")
                + ", counterpartyName=" + (counterpartyName == null ? "none" : "***")
                + "]";
    }
}
