package dev.abgleich.adapter.out.synthetic;

import dev.abgleich.adapter.out.synthetic.SyntheticDataset.Expected;
import dev.abgleich.adapter.out.synthetic.SyntheticDataset.Label;
import dev.abgleich.application.example.ExampleFile;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Random;

/**
 * Builds a Swiss camt.053 file and a Spanish Norma 43 file together with the invoices they pay.
 * The same seed and day always give byte-identical files, so loading the example twice is a
 * duplicate import (B21) and test expectations never drift.
 *
 * <p>Every name, account and reference is invented or a published specification example (B41).
 */
public final class SyntheticDataGenerator {

    public static final long DEFAULT_SEED = 20260915L;
    public static final LocalDate DEFAULT_DAY = LocalDate.of(2026, 9, 15);

    private static final Iban SWISS_QR_ACCOUNT = Iban.of("CH4431999123000889012");
    private static final Iban SWISS_ACCOUNT = Iban.of("CH9300762011623852957");
    private static final Iban SPANISH_ACCOUNT = Iban.of("ES9121000418450200051332");

    private static final List<String> SWISS_CUSTOMERS = List.of(
            "Muster Handwerk GmbH", "Beispiel Treuhand AG", "Alpenblick Hotel SA", "Zürcher Bäckerei Löwen",
            "Brunner & Co. AG", "Seeblick Architektur GmbH", "Keller Elektro AG", "Bergbahn Service GmbH",
            "Rheintal Logistik AG", "Uhrenatelier Jura Sàrl", "Glarner Holzbau AG", "Lindenhof Praxis GmbH");
    private static final List<String> SPANISH_CUSTOMERS = List.of(
            "TALLERES RUIZ SL", "PINTURAS SOL SA", "CONSTRUCCIONES IBAÑEZ SL", "FERRETERÍA PEÑA SL",
            "HOTEL MIRAMAR SA", "CLÍNICA DENTAL SUR SL", "DISTRIBUCIONES LEÓN SA");
    private static final String SPANISH_PERSON = "JOSÉ MUÑOZ ÁLVAREZ";

    private final Random random;
    private final LocalDate day;
    private final String ymd;
    private final List<RegisterInvoiceCommand> invoices = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();

    public SyntheticDataGenerator(long seed, LocalDate day) {
        this.random = new Random(seed);
        this.day = Objects.requireNonNull(day, "day");
        this.ymd = day.format(DateTimeFormatter.BASIC_ISO_DATE);
    }

    public static SyntheticDataset defaultDataset() {
        return new SyntheticDataGenerator(DEFAULT_SEED, DEFAULT_DAY).generate();
    }

    public SyntheticDataset generate() {
        invoices.clear();
        labels.clear();
        ExampleFile swiss = swissFile();
        ExampleFile spanish = spanishFile();
        return new SyntheticDataset(invoices, List.of(swiss, spanish), labels);
    }

    private ExampleFile swissFile() {
        String fileName = "example-ch-camt053-" + ymd + ".xml";
        List<String> customers = shuffled(SWISS_CUSTOMERS);
        CamtWriter camt = new CamtWriter("ABG-EXAMPLE-CH-" + ymd, day);

        // QR-IBAN account: QR-bill payments.
        CamtWriter.Statement qr = camt.statement("ABG-EXAMPLE-CH-" + ymd + "-QR", SWISS_QR_ACCOUNT, amount("CHF", 5_000_00, 20_000_00));
        List<Invoice> qrInvoices = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long sequence = 1001 + i;
            qrInvoices.add(invoice("F-2026-" + sequence, SWISS_QR_ACCOUNT, customers.get(i), amount("CHF", 150_00, 4_800_00),
                    new PaymentReference.Qrr(SyntheticReferences.qrr(Long.parseLong(ymd) * 10_000 + sequence))));
        }
        payExactly(fileName, qr, "EXCH" + ymd + "0001", qrInvoices.get(0));
        payExactly(fileName, qr, "EXCH" + ymd + "0002", qrInvoices.get(1));
        String batchReference = "EXCH" + ymd + "0003";
        qr.batchCredit(batchReference, List.of(exactPayment(qrInvoices.get(2)), exactPayment(qrInvoices.get(3)),
                exactPayment(qrInvoices.get(4))));
        for (int n = 1; n <= 3; n++) {
            labels.add(new Label(fileName, batchReference + "#" + n, qrInvoices.get(1 + n).number(), Expected.R1_AUTO_CONFIRM));
        }
        Invoice partial = qrInvoices.get(5);
        qr.credit("EXCH" + ymd + "0004", new Payment(partial.amount().subtract(Money.chf("50.00")), partial.debtor(),
                partial.referenceText(), null));
        labels.add(new Label(fileName, "EXCH" + ymd + "0004", partial.number(), Expected.R2_PARTIAL_PAYMENT));
        qr.debit("EXCH" + ymd + "0005", Money.chf("12.00"), "Kontoführungsgebühr");
        labels.add(new Label(fileName, "EXCH" + ymd + "0005", null, Expected.DEBIT));

        // Regular IBAN account: creditor references, free text and payments without any reference.
        CamtWriter.Statement regular = camt.statement("ABG-EXAMPLE-CH-" + ymd + "-STD", SWISS_ACCOUNT, amount("CHF", 1_000_00, 9_000_00));
        Invoice scor = invoice("F-2026-2001", SWISS_ACCOUNT, customers.get(6), amount("CHF", 150_00, 4_800_00),
                new PaymentReference.Scor(SyntheticReferences.scor(2026_2001)));
        Invoice typo = invoice("F-2026-2002", SWISS_ACCOUNT, customers.get(7), amount("CHF", 150_00, 4_800_00),
                new PaymentReference.Scor(SyntheticReferences.scor(2026_2002)));
        Invoice text = invoice("F-2026-2003", SWISS_ACCOUNT, customers.get(8), amount("CHF", 150_00, 4_800_00), null);
        Invoice byName = invoice("F-2026-2004", SWISS_ACCOUNT, customers.get(9), amount("CHF", 150_00, 4_800_00), null);
        invoice("F-2026-2005", SWISS_ACCOUNT, customers.get(10), amount("CHF", 150_00, 4_800_00), null);

        payExactly(fileName, regular, "EXCH" + ymd + "0101", scor);
        regular.credit("EXCH" + ymd + "0102", new Payment(typo.amount(), typo.debtor(),
                SyntheticReferences.withTypo(typo.referenceText()), null));
        labels.add(new Label(fileName, "EXCH" + ymd + "0102", typo.number(), Expected.REFERENCE_TYPO_REVIEW));
        regular.credit("EXCH" + ymd + "0103", new Payment(text.amount(), text.debtor(), null, "Rechnung " + text.number()));
        labels.add(new Label(fileName, "EXCH" + ymd + "0103", text.number(), Expected.R4_INVOICE_NUMBER_IN_TEXT));
        regular.credit("EXCH" + ymd + "0104", new Payment(byName.amount(), byName.debtor(), null, null));
        labels.add(new Label(fileName, "EXCH" + ymd + "0104", byName.number(), Expected.R5_PAYER_NAME));
        regular.credit("EXCH" + ymd + "0105", new Payment(amount("CHF", 20_00, 300_00), customers.get(11), null, "Spende"));
        labels.add(new Label(fileName, "EXCH" + ymd + "0105", null, Expected.NO_INVOICE));

        return new ExampleFile(fileName, "CH", "Swiss camt.053 with two accounts: QR-bill payments, a batch booking, "
                + "a partial payment, a mistyped reference and a bank fee", camt.toBytes());
    }

    private ExampleFile spanishFile() {
        String fileName = "example-es-norma43-" + ymd + ".n43";
        List<String> customers = shuffled(SPANISH_CUSTOMERS);
        Norma43Writer norma43 = new Norma43Writer(day);
        Norma43Writer.Account account = norma43.account("2100", "0418", "0200051332",
                amount("EUR", 2_000_00, 15_000_00), "DEMO ABGLEICH SL");

        Invoice first = invoice("FV-2026-0101", SPANISH_ACCOUNT, customers.get(0), amount("EUR", 90_00, 3_500_00),
                new PaymentReference.Scor(SyntheticReferences.scor(2026_0101)));
        Invoice second = invoice("FV-2026-0102", SPANISH_ACCOUNT, customers.get(1), amount("EUR", 90_00, 3_500_00),
                new PaymentReference.Scor(SyntheticReferences.scor(2026_0102)));
        Invoice text = invoice("FV-2026-0103", SPANISH_ACCOUNT, customers.get(2), amount("EUR", 90_00, 3_500_00), null);
        Invoice person = invoice("FV-2026-0104", SPANISH_ACCOUNT, SPANISH_PERSON, amount("EUR", 90_00, 3_500_00), null);
        invoice("FV-2026-0105", SPANISH_ACCOUNT, customers.get(3), amount("EUR", 90_00, 3_500_00), null);

        account.credit(first.amount(), first.referenceText(), "TRANSF " + first.debtor());
        labels.add(new Label(fileName, "movement-1", first.number(), Expected.R1_AUTO_CONFIRM));
        account.credit(second.amount(), second.referenceText(), "TRANSF " + second.debtor());
        labels.add(new Label(fileName, "movement-2", second.number(), Expected.R1_AUTO_CONFIRM));
        account.credit(text.amount(), null, "TRANSF " + text.debtor(), "FRA 103");
        labels.add(new Label(fileName, "movement-3", text.number(), Expected.R4_INVOICE_NUMBER_IN_TEXT));
        account.credit(person.amount(), null, "TRANSF " + person.debtor());
        labels.add(new Label(fileName, "movement-4", person.number(), Expected.R5_PAYER_NAME));
        // Two real, identical transfers on the same day: both must be stored (B16).
        Money fee = Money.eur("605.00");
        account.credit(fee, null, "CUOTA SERVICIO MANTENIMIENTO", "PINTURAS SOL SA");
        account.credit(fee, null, "CUOTA SERVICIO MANTENIMIENTO", "PINTURAS SOL SA");
        labels.add(new Label(fileName, "movement-5", null, Expected.NO_INVOICE));
        labels.add(new Label(fileName, "movement-6", null, Expected.NO_INVOICE));
        account.charge(Money.eur("3.50"), 4711, "COMISION MANTENIMIENTO");
        labels.add(new Label(fileName, "movement-7", null, Expected.DEBIT));

        return new ExampleFile(fileName, "ES", "Spanish Norma 43 in ISO-8859-1: creditor references, an invoice number "
                + "in the concept, a name with Ñ, two identical transfers and a commission", norma43.toBytes());
    }

    private void payExactly(String fileName, CamtWriter.Statement statement, String bankReference, Invoice invoice) {
        statement.credit(bankReference, exactPayment(invoice));
        labels.add(new Label(fileName, bankReference, invoice.number(), Expected.R1_AUTO_CONFIRM));
    }

    private static Payment exactPayment(Invoice invoice) {
        return new Payment(invoice.amount(), invoice.debtor(), invoice.referenceText(), null);
    }

    private Invoice invoice(String number, Iban creditor, String debtor, Money amount, PaymentReference reference) {
        invoices.add(new RegisterInvoiceCommand(InvoiceNumber.of(number), creditor, debtor, amount, reference,
                day.plusDays(30)));
        return new Invoice(number, debtor, amount, reference);
    }

    /** A random amount in whole multiples of 5 cents, the smallest coin in Switzerland. Never a double (B01). */
    private Money amount(String currency, long minCents, long maxCents) {
        long cents = minCents + random.nextLong((maxCents - minCents) / 5 + 1) * 5;
        return new Money(new BigDecimal(BigInteger.valueOf(cents), 2), Currency.getInstance(currency));
    }

    private List<String> shuffled(List<String> names) {
        List<String> copy = new ArrayList<>(names);
        Collections.shuffle(copy, random);
        return copy;
    }

    private record Invoice(String number, String debtor, Money amount, PaymentReference reference) {

        String referenceText() {
            return switch (reference) {
                case PaymentReference.Qrr qrr -> qrr.value().value();
                case PaymentReference.Scor scor -> scor.value().value();
                case PaymentReference.FreeText free -> free.value();
                case PaymentReference.None none -> null;
                case null -> null;
            };
        }
    }
}
