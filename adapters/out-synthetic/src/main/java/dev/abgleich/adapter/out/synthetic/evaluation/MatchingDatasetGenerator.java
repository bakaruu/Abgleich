package dev.abgleich.adapter.out.synthetic.evaluation;

import dev.abgleich.adapter.out.synthetic.evaluation.MatchingDataset.Case;
import dev.abgleich.adapter.out.synthetic.evaluation.MatchingDataset.Kind;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.checksum.Mod97;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.reference.QrReference;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 300 labelled Swiss and Spanish payments, deterministic for a seed. Besides the cases each rule should
 * find, it contains the traps that make a matcher look better than it is: the same amount from another
 * payer, similar company names, numbers in a text that are not invoice numbers, debits carrying a
 * reference and real ties.
 */
public final class MatchingDatasetGenerator {

    public static final long DEFAULT_SEED = 20261103L;
    public static final LocalDate DAY = LocalDate.of(2026, 10, 15);

    private static final Iban QR_ACCOUNT = Iban.of("CH4431999123000889012");
    private static final Iban CH_ACCOUNT = Iban.of("CH9300762011623852957");
    private static final Iban ES_ACCOUNT = Iban.of("ES9121000418450200051332");

    private static final List<String> SWISS_FIRST = List.of("Keller", "Brunner", "Meier", "Frei", "Huber", "Gerber",
            "Baumann", "Zürcher", "Alpenblick", "Rheintal", "Seeblick", "Lindenhof", "Bergbahn", "Muster", "Jura",
            "Glarner", "Aare", "Säntis", "Pilatus", "Emmental", "Bodensee", "Engadin", "Thurgau", "Ticino", "Wallis",
            "Rigi", "Limmat", "Reuss", "Toggenburg", "Napf");
    private static final List<String> SWISS_SECOND = List.of("Elektro AG", "Holzbau AG", "Treuhand AG",
            "Architektur GmbH", "Bäckerei GmbH", "Logistik AG", "Hotel SA", "Praxis GmbH", "Garage AG",
            "Druckerei GmbH", "Informatik AG", "Sanitär GmbH", "Malerei GmbH", "Gartenbau AG", "Uhren Sàrl", "Metallbau AG",
            "Reisen GmbH", "Käserei AG", "Optik GmbH", "Immobilien AG");
    private static final List<String> SPANISH_FIRST = List.of("TALLERES", "PINTURAS", "CONSTRUCCIONES",
            "FERRETERÍA", "HOTEL", "CLÍNICA", "DISTRIBUCIONES", "TRANSPORTES", "PANADERÍA", "ASESORÍA",
            "CARPINTERÍA", "LIMPIEZAS", "GRÁFICAS", "INSTALACIONES", "SUMINISTROS", "ÓPTICA", "BODEGAS", "FARMACIA",
            "AUTOESCUELA", "RECAMBIOS", "VIVEROS", "ELECTRICIDAD", "MUDANZAS", "ACADEMIA", "CONSERVAS");
    private static final List<String> SPANISH_SECOND = List.of("RUIZ SL", "SOL SA", "IBAÑEZ SL", "PEÑA SL",
            "MIRAMAR SA", "LEÓN SA", "GARCÍA SL", "MUÑOZ SL", "NORTE SA", "LEVANTE SL", "CASTILLA SA", "ARAGÓN SL",
            "DEL SUR SL", "MARTÍNEZ SL", "LUNA SA", "HERMANOS SL", "COSTA SA", "RIOJA SL", "GALICIA SA",
            "LÓPEZ SL");
    private static final List<String> SWISS_TEXTS = List.of("Rechnung %s", "Rechnung Nr. %s", "RG %s besten Dank",
            "Invoice %s", "Zahlung Rechnung %s");
    private static final List<String> SPANISH_TEXTS = List.of("TRANSF %s FRA %s", "PAGO FACTURA %s", "FRA %s",
            "N/REF FACTURA %s SEPTIEMBRE");
    private static final List<String> NOISE = List.of("Spende", "Rückerstattung Kaution", "CUOTA 15 10 2026",
            "TRANSFERENCIA NOMINA", "Mitgliederbeitrag 2026", "DEVOLUCION FIANZA", "Sammelzahlung", "ALQUILER OCTUBRE");

    private final Random random;
    private final Set<String> usedNames = new LinkedHashSet<>();
    private final List<Invoice> invoices = new ArrayList<>();
    private final List<Case> cases = new ArrayList<>();
    private int serialCh = 1000;
    private int serialEs;
    private int caseNumber;

    public MatchingDatasetGenerator(long seed) {
        this.random = new Random(seed);
    }

    public static MatchingDataset defaultDataset() {
        return new MatchingDatasetGenerator(DEFAULT_SEED).generate();
    }

    public MatchingDataset generate() {
        repeat(20, () -> exact(QR_ACCOUNT, Kind.R1_QR_REFERENCE));
        repeat(20, () -> exact(QR_ACCOUNT, Kind.R1_QR_REFERENCE));
        repeat(20, () -> exact(CH_ACCOUNT, Kind.R1_CREDITOR_REFERENCE));
        repeat(20, () -> exact(ES_ACCOUNT, Kind.R1_CREDITOR_REFERENCE));
        repeat(15, this::partial);
        repeat(10, this::overpayment);
        repeat(15, this::charges);
        repeat(10, this::closedInvoice);
        repeat(20, this::typo);
        repeat(35, this::invoiceNumberInText);
        repeat(30, this::payerName);
        repeat(15, this::severalInvoices);
        repeat(10, this::tie);
        repeat(25, this::noInvoice);
        repeat(15, this::lookalike);
        repeat(20, this::debit);
        // Background invoices nobody pays, so every rule searches a realistic ledger.
        repeat(60, () -> invoice(pick(QR_ACCOUNT, CH_ACCOUNT, ES_ACCOUNT), newName(), randomAmount(50_00, 8_000_00),
                DAY.plusDays(random.nextInt(-40, 40))));
        return new MatchingDataset(invoices, cases);
    }

    private void exact(Iban account, Kind kind) {
        Invoice invoice = invoice(account, newName(), randomAmount(50_00, 9_000_00), dueNear());
        add(kind, credit(account, invoice.amount(), invoice.reference(), null, randomPayer()), invoice);
    }

    private void partial() {
        Iban account = pick(CH_ACCOUNT, ES_ACCOUNT);
        Invoice invoice = invoice(account, newName(), randomAmount(400_00, 9_000_00), dueNear());
        Money paid = fraction(invoice.amount(), 30, 70);
        add(Kind.R2_PARTIAL_PAYMENT, credit(account, paid, invoice.reference(), null, randomPayer()), invoice);
    }

    private void overpayment() {
        Iban account = pick(CH_ACCOUNT, QR_ACCOUNT);
        Invoice invoice = invoice(account, newName(), randomAmount(100_00, 5_000_00), dueNear());
        Money paid = invoice.amount().add(fraction(invoice.amount(), 5, 20));
        add(Kind.R2_OVERPAYMENT, credit(account, paid, invoice.reference(), null, randomPayer()), invoice);
    }

    /** B07: a foreign payment a few units short, sometimes with the charges reported by the bank. */
    private void charges() {
        Iban account = pick(CH_ACCOUNT, QR_ACCOUNT, ES_ACCOUNT);
        Invoice invoice = invoice(account, newName(), randomAmount(1_600_00, 9_000_00), dueNear());
        Money shortfall = amountIn(invoice.amount().currency(), 5, 2_500);
        boolean reported = random.nextBoolean();
        PaymentToMatch payment = new PaymentToMatch(UUID.randomUUID(), account, Direction.CREDIT,
                invoice.amount().subtract(shortfall), invoice.reference(), null, randomPayer(), null,
                reported ? shortfall : null, false, DAY, 0);
        add(Kind.R2_BANK_CHARGES, payment, invoice);
    }

    /** B31: money for a paid or cancelled invoice must be seen, never absorbed. */
    private void closedInvoice() {
        Iban account = pick(CH_ACCOUNT, ES_ACCOUNT);
        Invoice open = invoice(account, newName(), randomAmount(100_00, 3_000_00), dueNear());
        Invoice closed = random.nextBoolean() ? open.withConfirmedPayment(open.amount()) : open.cancel();
        invoices.set(invoices.indexOf(open), closed);
        add(Kind.R2_CLOSED_INVOICE, credit(account, closed.amount(), closed.reference(), null, randomPayer()), closed);
    }

    private void typo() {
        Iban account = pick(QR_ACCOUNT, CH_ACCOUNT, ES_ACCOUNT);
        Invoice invoice = invoice(account, newName(), randomAmount(50_00, 5_000_00), dueNear());
        String reference = referenceText(invoice.reference());
        int position = random.nextInt(4, reference.length());
        char wrong = (char) ('0' + (reference.charAt(position) - '0' + 1 + random.nextInt(8)) % 10);
        String typed = reference.substring(0, position) + wrong + reference.substring(position + 1);
        add(Kind.R3_REFERENCE_TYPO, credit(account, invoice.amount(), PaymentReference.parse(typed), null,
                randomPayer()), invoice);
    }

    private void invoiceNumberInText() {
        boolean spanish = random.nextBoolean();
        Iban account = spanish ? ES_ACCOUNT : CH_ACCOUNT;
        Invoice invoice = invoiceWithoutReference(account, newName(), randomAmount(50_00, 6_000_00), dueNear());
        String serial = invoice.number().value().replaceAll("^.*-0*", "");
        String text = spanish
                ? String.format(SPANISH_TEXTS.get(random.nextInt(SPANISH_TEXTS.size())), serial, serial)
                : String.format(SWISS_TEXTS.get(random.nextInt(SWISS_TEXTS.size())),
                        random.nextBoolean() ? serial : invoice.number().value());
        add(Kind.R4_INVOICE_NUMBER_IN_TEXT, credit(account, invoice.amount(), PaymentReference.none(), text,
                randomPayer()), invoice);
    }

    /**
     * R5: the payer writes the name differently: legal form, case, accents, word order, a typo. Spanish files
     * without a payer field carry the name in the concept, with the exact amount (Norma 43).
     */
    private void payerName() {
        Iban account = pick(CH_ACCOUNT, ES_ACCOUNT);
        String debtor = newName();
        Invoice invoice = invoiceWithoutReference(account, debtor, randomAmount(100_00, 6_000_00), dueNear());
        if (account.equals(ES_ACCOUNT) && random.nextBoolean()) {
            String concept = "TRANSF " + java.text.Normalizer.normalize(debtor.toUpperCase(Locale.ROOT),
                    java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            add(Kind.R5_PAYER_NAME, credit(account, invoice.amount(), PaymentReference.none(), concept, null), invoice);
            return;
        }
        Money paid = random.nextInt(3) == 0 ? fraction(invoice.amount(), 40, 90) : invoice.amount();
        add(Kind.R5_PAYER_NAME, credit(account, paid, PaymentReference.none(), null, variant(debtor)), invoice);
    }

    private void severalInvoices() {
        Iban account = pick(CH_ACCOUNT, ES_ACCOUNT);
        String debtor = newName();
        int count = random.nextInt(2, 4);
        List<Invoice> group = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            group.add(invoiceWithoutReference(account, debtor, randomAmount(80_00, 2_500_00), dueNear()));
        }
        Money total = group.stream().map(Invoice::amount).reduce(Money::add).orElseThrow();
        String numbers = group.stream().map(i -> i.number().value().replaceAll("^.*-0*", ""))
                .collect(Collectors.joining(" Y "));
        String text = random.nextBoolean() ? "PAGO FACTURAS " + numbers : null;
        PaymentToMatch payment = credit(account, total, PaymentReference.none(), text, variant(debtor));
        cases.add(new Case(nextId(), Kind.R6_SEVERAL_INVOICES, payment,
                Set.of(group.stream().map(Invoice::id).collect(Collectors.toSet()))));
    }

    /** B28: two open invoices of one customer with the same amount, paid by name only. */
    private void tie() {
        Iban account = pick(CH_ACCOUNT, ES_ACCOUNT);
        String debtor = newName();
        Money amount = randomAmount(100_00, 3_000_00);
        Invoice first = invoiceWithoutReference(account, debtor, amount, dueNear());
        Invoice second = invoiceWithoutReference(account, debtor, amount, dueNear());
        cases.add(new Case(nextId(), Kind.TIE, credit(account, amount, PaymentReference.none(), null, variant(debtor)),
                Set.of(Set.of(first.id()), Set.of(second.id()))));
    }

    private void noInvoice() {
        Iban account = pick(QR_ACCOUNT, CH_ACCOUNT, ES_ACCOUNT);
        cases.add(new Case(nextId(), Kind.NO_INVOICE, credit(account, randomAmount(10_00, 4_000_00),
                PaymentReference.none(), NOISE.get(random.nextInt(NOISE.size())), newName()), Set.of()));
    }

    /**
     * Traps: an existing invoice's exact amount from an unrelated payer; a payer whose company shares a
     * word with a debtor ("PINTURAS SOL" and "PINTURAS LUNA"); a number in the text that is not announced
     * as an invoice.
     */
    private void lookalike() {
        Iban account = pick(CH_ACCOUNT, ES_ACCOUNT);
        String debtor = newName();
        Invoice invoice = invoiceWithoutReference(account, debtor, randomAmount(100_00, 3_000_00), dueNear());
        String serial = invoice.number().value().replaceAll("^.*-0*", "");
        String payer = switch (random.nextInt(3)) {
            case 0 -> newName();
            case 1 -> lookalikeOf(debtor);
            default -> newName();
        };
        String text = switch (random.nextInt(3)) {
            case 0 -> "CUOTA " + serial + " OCTUBRE";
            // A company with a similar name inside a Norma 43 concept, without a payer field.
            case 1 -> "TRANSF " + lookalikeOf(debtor).toUpperCase(Locale.ROOT);
            default -> null;
        };
        boolean nameInText = text != null && text.startsWith("TRANSF");
        cases.add(new Case(nextId(), Kind.LOOKALIKE_NO_INVOICE,
                credit(account, invoice.amount(), PaymentReference.none(), text, nameInText ? null : payer), Set.of()));
    }

    private void debit() {
        Iban account = pick(QR_ACCOUNT, CH_ACCOUNT, ES_ACCOUNT);
        Invoice invoice = invoice(account, newName(), randomAmount(50_00, 3_000_00), dueNear());
        PaymentToMatch refund = new PaymentToMatch(UUID.randomUUID(), account, Direction.DEBIT, invoice.amount(),
                invoice.reference(), "Rückzahlung " + invoice.number(), invoice.debtorName(), null, null, false, DAY, 0);
        cases.add(new Case(nextId(), Kind.DEBIT, refund, Set.of()));
    }

    private void add(Kind kind, PaymentToMatch payment, Invoice invoice) {
        cases.add(new Case(nextId(), kind, payment, Set.of(Set.of(invoice.id()))));
    }

    /** QR-IBAN invoices carry a QR reference; other accounts a creditor reference. */
    private Invoice invoice(Iban account, String debtor, Money amount, LocalDate due) {
        PaymentReference reference = account.isQrIban() ? qrr() : scor();
        return register(account, debtor, amount.currency().equals(currencyOf(account)) ? amount
                : new Money(amount.amount(), currencyOf(account)), due, reference);
    }

    private Invoice invoiceWithoutReference(Iban account, String debtor, Money amount, LocalDate due) {
        return register(account, debtor, new Money(amount.amount(), currencyOf(account)), due, PaymentReference.none());
    }

    private Invoice register(Iban account, String debtor, Money amount, LocalDate due, PaymentReference reference) {
        String number = account.countryCode().equals("ES")
                ? String.format("FV-2026-%04d", ++serialEs)
                : String.format("F-2026-%04d", ++serialCh);
        Invoice invoice = Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), account, debtor, amount,
                account.isQrIban() || !reference.equals(PaymentReference.none()) ? reference : PaymentReference.none(),
                due);
        invoices.add(invoice);
        return invoice;
    }

    private PaymentToMatch credit(Iban account, Money amount, PaymentReference reference, String text, String payer) {
        return new PaymentToMatch(UUID.randomUUID(), account, Direction.CREDIT,
                new Money(amount.amount(), currencyOf(account)), reference, text, payer, null, null, false, DAY, 0);
    }

    private PaymentReference qrr() {
        String digits = String.format("%026d", 2026_1015_0000L + invoices.size() * 7L + random.nextInt(5));
        return new PaymentReference.Qrr(QrReference.of(digits + QrReference.checkDigitFor(digits)));
    }

    private PaymentReference scor() {
        String body = String.format("%012d", 3_000_000L + invoices.size() * 13L + random.nextInt(10));
        int check = 98 - Mod97.remainder(body + "RF00");
        return new PaymentReference.Scor(CreditorReference.of(String.format("RF%02d%s", check, body)));
    }

    private String newName() {
        for (int attempt = 0; attempt < 1_000; attempt++) {
            boolean spanish = random.nextBoolean();
            String name = spanish
                    ? SPANISH_FIRST.get(random.nextInt(SPANISH_FIRST.size())) + " " + SPANISH_SECOND.get(random.nextInt(SPANISH_SECOND.size()))
                    : SWISS_FIRST.get(random.nextInt(SWISS_FIRST.size())) + " " + SWISS_SECOND.get(random.nextInt(SWISS_SECOND.size()));
            if (usedNames.add(name)) {
                return name;
            }
        }
        throw new IllegalStateException("Name lists are exhausted");
    }

    private String randomPayer() {
        return random.nextInt(4) == 0 ? null : newName();
    }

    /**
     * A company sharing the debtor's first word but not its second ("PINTURAS LUNA SA" for "PINTURAS SOL SA").
     * It must not be the name of another real customer, or proposing that customer's invoice would be right.
     */
    private String lookalikeOf(String debtor) {
        List<String> seconds = debtor.equals(debtor.toUpperCase(Locale.ROOT)) ? SPANISH_SECOND : SWISS_SECOND;
        String first = debtor.split(" ")[0];
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = first + " " + seconds.get(random.nextInt(seconds.size()));
            if (!candidate.equals(debtor) && usedNames.add(candidate)) {
                return candidate;
            }
        }
        return newName();
    }

    /** How payers really write a name: other legal form, upper case, no accents, other order, one typo. */
    private String variant(String name) {
        return switch (random.nextInt(5)) {
            case 0 -> name.toUpperCase(Locale.ROOT);
            case 1 -> name.replace(" AG", " A.G.").replace(" SL", " S.L.").replace(" SA", " S.A.")
                    .replace(" GmbH", "").replace(" Sàrl", "");
            case 2 -> java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            case 3 -> {
                String[] words = name.split(" ");
                yield words.length > 2 ? words[1] + " " + words[0] + " " + words[2] : name;
            }
            default -> {
                String first = name.split(" ")[0];
                int position = Math.min(3, first.length() - 1);
                yield first.substring(0, position) + first.charAt(position) + first.substring(position)
                        + name.substring(first.length());
            }
        };
    }

    private LocalDate dueNear() {
        return DAY.plusDays(random.nextInt(-20, 21));
    }

    private Money randomAmount(long minCents, long maxCents) {
        long cents = minCents + random.nextLong((maxCents - minCents) / 5 + 1) * 5;
        return new Money(new BigDecimal(BigInteger.valueOf(cents), 2), Money.CHF);
    }

    private Money amountIn(Currency currency, long minCents, long maxCents) {
        long cents = minCents + random.nextLong((maxCents - minCents) / 5 + 1) * 5;
        return new Money(new BigDecimal(BigInteger.valueOf(cents), 2), currency);
    }

    private Money fraction(Money amount, int minPercent, int maxPercent) {
        BigDecimal percent = BigDecimal.valueOf(random.nextInt(minPercent, maxPercent + 1));
        BigDecimal cents = amount.amount().multiply(percent).setScale(0, java.math.RoundingMode.DOWN);
        return new Money(new BigDecimal(cents.toBigInteger(), 2), amount.currency());
    }

    private static Currency currencyOf(Iban account) {
        return account.countryCode().equals("ES") ? Money.EUR : Money.CHF;
    }

    private static String referenceText(PaymentReference reference) {
        return switch (reference) {
            case PaymentReference.Qrr qrr -> qrr.value().value();
            case PaymentReference.Scor scor -> scor.value().value();
            case PaymentReference.FreeText text -> text.value();
            case PaymentReference.None none -> "";
        };
    }

    @SafeVarargs
    private <T> T pick(T... options) {
        return options[random.nextInt(options.length)];
    }

    private void repeat(int times, Runnable generator) {
        for (int i = 0; i < times; i++) {
            generator.run();
        }
    }

    private String nextId() {
        return String.format("case-%03d", ++caseNumber);
    }
}
