package dev.abgleich.adapter.out.synthetic;

import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.reference.QrReference;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * A statement of any size, with the invoices its payments settle, for measuring how the system behaves under
 * load rather than guessing. Deterministic for a given seed and size, and synthetic like everything else (B41).
 *
 * <p>The mix is what a working day looks like rather than the easiest case: most payments carry an exact QR
 * reference (R1), some are a few francs short (R2), some name the invoice only in the remittance text (R4), and
 * some belong to nobody, so the matcher does real work on every kind of payment.
 *
 * @param camtFile the whole statement as camt.053 bytes, for measuring the parser and a real import
 * @param invoices what to register before importing, so the payments have something to match
 * @param payments the same payments as domain objects, for measuring matching without a database
 * @param openInvoices the same invoices as domain objects, in the state the matcher would read them
 */
public record LoadDataset(
        byte[] camtFile,
        List<RegisterInvoiceCommand> invoices,
        List<PaymentToMatch> payments,
        List<Invoice> openInvoices,
        int transactions) {

    private static final Iban QR_ACCOUNT = Iban.of("CH4431999123000889012");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 15);
    private static final List<String> NAMES = List.of("Keller Elektro AG", "Brunner Holzbau AG", "Meier Treuhand AG",
            "Frei Gartenbau AG", "Huber Sanitär AG", "Gerber Malerei AG", "Widmer Transport AG", "Baumann Bau AG");

    public LoadDataset {
        camtFile = camtFile.clone();
        invoices = List.copyOf(invoices);
        payments = List.copyOf(payments);
        openInvoices = List.copyOf(openInvoices);
        if (transactions < 1) {
            throw new IllegalArgumentException("A load dataset needs at least one transaction");
        }
    }

    @Override
    public byte[] camtFile() {
        return camtFile.clone();
    }

    /**
     * @param transactions how many credits the statement holds; one invoice is created per transaction that is
     *     meant to match, so the matcher searches a growing set of open invoices
     */
    public static LoadDataset of(int transactions, long seed) {
        Random random = new Random(seed);
        List<RegisterInvoiceCommand> invoices = new ArrayList<>();
        List<Invoice> openInvoices = new ArrayList<>();
        List<PaymentToMatch> payments = new ArrayList<>();
        CamtWriter writer = new CamtWriter("LOAD-" + transactions, DAY);
        CamtWriter.Statement statement = writer.statement("LOAD-STMT", QR_ACCOUNT, Money.chf("0.00"));

        for (int i = 0; i < transactions; i++) {
            String number = "F-2026-" + (100000 + i);
            String payer = NAMES.get(random.nextInt(NAMES.size()));
            Money amount = Money.chf((50 + random.nextInt(9950)) + "." + twoDigits(random));
            QrReference reference = SyntheticReferences.qrr(i + 1L);
            LocalDate due = DAY.plusDays(random.nextInt(60) - 30);
            int kind = i % 10;

            if (kind == 9) {
                // Money nobody expected: no invoice, no reference. The matcher must find nothing.
                Payment orphan = new Payment(amount, payer, null, "Spende");
                statement.credit("LOAD-" + i, orphan);
                payments.add(toMatch(orphan));
                continue;
            }

            RegisterInvoiceCommand command = new RegisterInvoiceCommand(InvoiceNumber.of(number), QR_ACCOUNT, payer,
                    amount, new PaymentReference.Qrr(reference), due);
            invoices.add(command);
            openInvoices.add(command.newInvoice(UUID.randomUUID()));

            Payment payment = switch (kind) {
                // A payment a few francs short: bank charges or a partial payment, for review (R2).
                case 7 -> new Payment(amount.subtract(Money.chf("4.50")), payer, reference.value(), null);
                // The invoice number only in the text, no structured reference (R4).
                case 8 -> new Payment(amount, payer, null, "Rechnung " + number);
                // The common case: exact reference and amount (R1).
                default -> new Payment(amount, payer, reference.value(), null);
            };
            statement.credit("LOAD-" + i, payment);
            payments.add(toMatch(payment));
        }
        return new LoadDataset(writer.toBytes(), invoices, payments, openInvoices, transactions);
    }

    private static PaymentToMatch toMatch(Payment payment) {
        PaymentReference reference = payment.structuredReference() == null
                ? PaymentReference.none()
                : new PaymentReference.Qrr(QrReference.of(payment.structuredReference()));
        return new PaymentToMatch(UUID.randomUUID(), QR_ACCOUNT, Direction.CREDIT, payment.amount(), reference,
                payment.remittance(), payment.payer(), null, null, false, DAY, 0);
    }

    private static String twoDigits(Random random) {
        int cents = random.nextInt(100);
        return cents < 10 ? "0" + cents : String.valueOf(cents);
    }

    public int sizeInBytes() {
        return camtFile.length;
    }

    public String describe() {
        return transactions + " transactions, " + invoices.size() + " invoices, "
                + sizeInBytes() / 1024 + " KB of camt.053";
    }
}
