package dev.abgleich.adapter.out.synthetic;

import dev.abgleich.application.example.ExampleFile;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import java.util.List;
import java.util.Objects;

/**
 * Invoices, the bank files that pay them and, for every payment, what a correct reconciliation does
 * with it. The labels let phase F2 measure matching precision per rule instead of guessing.
 */
public record SyntheticDataset(List<RegisterInvoiceCommand> invoices, List<ExampleFile> files, List<Label> labels) {

    public SyntheticDataset {
        invoices = List.copyOf(invoices);
        files = List.copyOf(files);
        labels = List.copyOf(labels);
    }

    /** The expected result for a credit or debit, identified by its file and bank reference or line. */
    public enum Expected {
        /** Exact structured reference and amount: confirmed automatically by R1. */
        R1_AUTO_CONFIRM,
        /** Right reference, different amount: a partial payment for review (R2, phase F2). */
        R2_PARTIAL_PAYMENT,
        /** A reference with a typo: never matched automatically, goes to review (B04). */
        REFERENCE_TYPO_REVIEW,
        /** Invoice number only in the remittance text (R4, phase F2). */
        R4_INVOICE_NUMBER_IN_TEXT,
        /** No reference, payer name matches the debtor (R5, phase F2). */
        R5_PAYER_NAME,
        /** Money that belongs to no invoice. */
        NO_INVOICE,
        /** A debit: never pays an invoice (B10). */
        DEBIT
    }

    /** @param invoiceNumber the invoice the payment belongs to, or {@code null} */
    public record Label(String fileName, String paymentKey, String invoiceNumber, Expected expected) {
        public Label {
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(paymentKey, "paymentKey");
            Objects.requireNonNull(expected, "expected");
        }
    }

    public long count(Expected expected) {
        return labels.stream().filter(label -> label.expected() == expected).count();
    }
}
