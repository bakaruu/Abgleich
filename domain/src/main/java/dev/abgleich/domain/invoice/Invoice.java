package dev.abgleich.domain.invoice;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An invoice sent to a customer. Its status is never stored by hand: it follows from the amount
 * paid through confirmed allocations.
 *
 * <p>Immutable: a payment returns a new invoice with the same {@code version}; the repository stores
 * it only if nobody else changed the invoice in between (B22).
 *
 * @param reference a Swiss QR reference only with a QR-IBAN, a creditor reference only with a regular
 *     IBAN, or none
 */
public record Invoice(
        UUID id,
        InvoiceNumber number,
        Iban creditorAccount,
        String debtorName,
        Money amount,
        PaymentReference reference,
        LocalDate dueDate,
        Money paidAmount,
        boolean cancelled,
        long version) {

    private static final int MAX_NAME_LENGTH = 140;

    public Invoice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(creditorAccount, "creditorAccount");
        Objects.requireNonNull(debtorName, "debtorName");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(paidAmount, "paidAmount");
        reference = Objects.requireNonNullElseGet(reference, PaymentReference::none);
        debtorName = debtorName.strip();
        if (debtorName.isEmpty() || debtorName.length() > MAX_NAME_LENGTH) {
            throw new InvalidInvoiceException("Debtor name must have between 1 and " + MAX_NAME_LENGTH + " characters");
        }
        if (!amount.isPositive()) {
            throw new InvalidInvoiceException("Invoice amount must be positive");
        }
        if (!paidAmount.hasSameCurrencyAs(amount) || paidAmount.isNegative()) {
            throw new InvalidInvoiceException("Paid amount must be zero or positive and in the invoice currency");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version cannot be negative");
        }
        requireReferenceFitsAccount(reference, creditorAccount);
    }

    public static Invoice register(UUID id, InvoiceNumber number, Iban creditorAccount, String debtorName,
            Money amount, PaymentReference reference, LocalDate dueDate) {
        return new Invoice(id, number, creditorAccount, debtorName, amount, reference, dueDate,
                Money.zero(amount.currency()), false, 0);
    }

    public InvoiceStatus status() {
        if (cancelled) {
            return InvoiceStatus.CANCELLED;
        }
        if (paidAmount.isZero()) {
            return InvoiceStatus.OPEN;
        }
        int comparison = paidAmount.compareTo(amount);
        if (comparison < 0) {
            return InvoiceStatus.PARTIALLY_PAID;
        }
        return comparison == 0 ? InvoiceStatus.PAID : InvoiceStatus.OVERPAID;
    }

    /** What the customer still owes; negative when the invoice is overpaid. */
    public Money outstanding() {
        return amount.subtract(paidAmount);
    }

    /** Applies a confirmed allocation. A cancelled invoice never takes money silently. */
    public Invoice withConfirmedPayment(Money payment) {
        Objects.requireNonNull(payment, "payment");
        if (cancelled) {
            throw new InvalidInvoiceException("Invoice " + number + " is cancelled and cannot receive payments");
        }
        if (!payment.isPositive()) {
            throw new InvalidInvoiceException("A payment must be positive");
        }
        return new Invoice(id, number, creditorAccount, debtorName, amount, reference, dueDate,
                paidAmount.add(payment), false, version);
    }

    private static void requireReferenceFitsAccount(PaymentReference reference, Iban account) {
        switch (reference) {
            case PaymentReference.Qrr qrr -> {
                if (!account.isQrIban()) {
                    throw new InvalidInvoiceException("A QR reference requires a QR-IBAN as creditor account");
                }
            }
            case PaymentReference.Scor scor -> {
                if (account.isQrIban()) {
                    throw new InvalidInvoiceException("A QR-IBAN only accepts QR references, not creditor references");
                }
            }
            case PaymentReference.FreeText text ->
                    throw new InvalidInvoiceException("An invoice reference must be a QR reference, a creditor reference or none");
            case PaymentReference.None none -> {
                // Invoices without a structured reference are matched by text rules later.
            }
        }
    }

    /** Leaves out the debtor name, which is personal data (B41). */
    @Override
    public String toString() {
        return "Invoice[" + number + ", " + amount + ", paid " + paidAmount + ", " + status() + ", v" + version + "]";
    }
}
