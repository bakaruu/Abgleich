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
 */
public record TransactionDetail(
        Money amount,
        PaymentReference reference,
        String remittanceText,
        String counterpartyName,
        String endToEndId,
        String bankReference) {

    public TransactionDetail {
        if (amount != null && !amount.isPositive()) {
            throw new InvalidStatementException(
                    InvalidStatementException.Reason.INCONSISTENT_ENTRY, "Transaction amount must be positive");
        }
        reference = Objects.requireNonNullElseGet(reference, PaymentReference::none);
        remittanceText = Texts.blankToNull(remittanceText);
        counterpartyName = Texts.blankToNull(counterpartyName);
        endToEndId = Texts.blankToNull(endToEndId);
        bankReference = Texts.blankToNull(bankReference);
    }

    TransactionDetail withAmount(Money newAmount) {
        return new TransactionDetail(newAmount, reference, remittanceText, counterpartyName, endToEndId, bankReference);
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
