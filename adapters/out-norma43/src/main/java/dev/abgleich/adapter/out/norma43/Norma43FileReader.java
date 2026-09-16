package dev.abgleich.adapter.out.norma43;

import dev.abgleich.application.port.out.ParsedStatementFile;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Reads the records of one Norma 43 file in order. Single use: create one per file.
 *
 * <p>The file can hold several accounts, each one a record 11, its movements (22 with up to five
 * 23 and an optional 24) and a record 33 with totals (B20). A record 88 closes the file and states
 * how many records came before it, so a truncated file is always detected.
 */
final class Norma43FileReader {

    static final int RECORD_LENGTH = 80;
    private static final int MAX_CONCEPT_RECORDS = 5;

    /** Two-digit years always belong to 2000-2099, never to the 1900s (B17). */
    private static final DateTimeFormatter DATE = new DateTimeFormatterBuilder()
            .appendValueReduced(ChronoField.YEAR, 2, 2, 2000)
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .toFormatter()
            .withResolverStyle(ResolverStyle.STRICT);

    private static final Map<Integer, Currency> CURRENCIES_BY_NUMBER = Currency.getAvailableCurrencies().stream()
            .filter(currency -> currency.getNumericCode() > 0)
            .collect(Collectors.toMap(Currency::getNumericCode, currency -> currency, (first, second) -> first));

    private final BoundedLineReader lines;
    private final List<Statement> statements = new ArrayList<>();
    private AccountBuilder account;
    private EntryBuilder entry;
    private int recordCount;
    private boolean ended;

    Norma43FileReader(BoundedLineReader lines) {
        this.lines = lines;
    }

    ParsedStatementFile read() throws IOException {
        for (String line = lines.next(); line != null; line = lines.next()) {
            try {
                readLine(line);
            } catch (InvalidStatementException e) {
                throw new InvalidStatementException(e.reason(), "Line " + lines.lineNumber() + ": " + e.getMessage(), e);
            }
        }
        if (!ended) {
            throw malformed("The file has no end record 88; it may be truncated");
        }
        if (statements.isEmpty()) {
            throw malformed("The file contains no account");
        }
        return new ParsedStatementFile(StatementFormat.NORMA43, null, statements);
    }

    private void readLine(String rawLine) {
        // Blank lines and a trailing DOS end-of-file character are not records.
        if (rawLine.isBlank() || rawLine.equals("")) {
            return;
        }
        if (ended) {
            throw malformed("There is data after the end record 88");
        }
        String record = toRecordLength(rawLine);
        switch (record.substring(0, 2)) {
            case "11" -> startAccount(record);
            case "22" -> startEntry(record);
            case "23" -> addConcepts(record);
            case "24" -> requireEntry("24");
            case "33" -> finishAccount(record);
            case "88" -> finishFile(record);
            default -> throw malformed("Unknown record type '" + record.substring(0, 2) + "'");
        }
        if (!ended) {
            recordCount++;
        }
    }

    /** Some tools trim trailing spaces or add them; only the first 80 characters carry data (B20). */
    private static String toRecordLength(String line) {
        if (line.length() > RECORD_LENGTH) {
            if (!line.substring(RECORD_LENGTH).isBlank()) {
                throw malformed("The record is longer than " + RECORD_LENGTH + " characters");
            }
            return line.substring(0, RECORD_LENGTH);
        }
        return line + " ".repeat(RECORD_LENGTH - line.length());
    }

    private void startAccount(String record) {
        if (account != null) {
            throw malformed("Record 11 found before the totals record 33 of the previous account");
        }
        Currency currency = currency(field(record, 48, 50));
        account = new AccountBuilder(
                digits(record, 3, 6, "bank code"),
                digits(record, 7, 10, "branch code"),
                digits(record, 11, 20, "account number"),
                date(record, 21, 26, "start date"),
                date(record, 27, 32, "end date"),
                signedBalance(record, 33, 34, 47, currency, "opening balance"),
                currency);
    }

    private void startEntry(String record) {
        if (account == null) {
            throw malformed("Record 22 found outside an account (missing record 11)");
        }
        finishEntry();
        entry = new EntryBuilder(
                date(record, 11, 16, "operation date"),
                date(record, 17, 22, "value date"),
                direction(field(record, 28, 28)),
                amount(record, 29, 42, account.currency, "movement amount"),
                field(record, 43, 52),
                field(record, 53, 64),
                field(record, 65, 80));
    }

    private void addConcepts(String record) {
        requireEntry("23");
        if (entry.concepts.size() == MAX_CONCEPT_RECORDS * 2) {
            throw malformed("A movement has more than " + MAX_CONCEPT_RECORDS + " concept records 23");
        }
        entry.concepts.add(field(record, 5, 42));
        entry.concepts.add(field(record, 43, 80));
    }

    private void requireEntry(String recordType) {
        if (entry == null) {
            throw malformed("Record " + recordType + " found without a movement record 22");
        }
    }

    private void finishEntry() {
        if (entry != null) {
            account.entries.add(entry.build());
            entry = null;
        }
    }

    private void finishAccount(String record) {
        if (account == null) {
            throw malformed("Record 33 found without an account header 11");
        }
        finishEntry();
        String accountId = field(record, 3, 20);
        if (!accountId.equals(account.bank + account.branch + account.number)) {
            throw malformed("Record 33 belongs to a different account than its record 11");
        }
        Currency currency = currency(field(record, 74, 76));
        if (!currency.equals(account.currency)) {
            throw new InvalidStatementException(Reason.MIXED_CURRENCIES,
                    "Record 33 is in " + currency + " but its account is in " + account.currency);
        }
        account.requireTotals(
                count(record, 21, 25, "debit count"), amount(record, 26, 39, currency, "debit total"),
                count(record, 40, 44, "credit count"), amount(record, 45, 58, currency, "credit total"));
        Money closing = signedBalance(record, 59, 60, 73, currency, "closing balance");
        statements.add(account.build(closing));
        account = null;
    }

    private void finishFile(String record) {
        if (account != null) {
            throw malformed("The end record 88 comes before the totals record 33 of the last account");
        }
        if (!field(record, 3, 20).equals("9".repeat(18))) {
            throw malformed("The end record 88 must contain 18 nines");
        }
        int declared = count(record, 21, 26, "record count");
        if (declared != recordCount) {
            throw malformed("The end record 88 declares " + declared + " records but the file has " + recordCount);
        }
        ended = true;
    }

    /** 1-based, inclusive positions, as written in the AEB specification. */
    private static String field(String record, int from, int to) {
        return record.substring(from - 1, to);
    }

    private static String digits(String record, int from, int to, String name) {
        String value = field(record, from, to);
        if (!value.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw malformed("The " + name + " (positions " + from + "-" + to + ") must be numeric");
        }
        return value;
    }

    private static int count(String record, int from, int to, String name) {
        return Integer.parseInt(digits(record, from, to, name));
    }

    /** Amounts have two implied decimals: "00000000181500" is 1815.00, not 181500 (B18). */
    private static Money amount(String record, int from, int to, Currency currency, String name) {
        BigInteger cents = new BigInteger(digits(record, from, to, name));
        return new Money(new BigDecimal(cents, 2), currency);
    }

    private static Money signedBalance(String record, int signPosition, int from, int to, Currency currency,
            String name) {
        Money amount = amount(record, from, to, currency, name);
        return switch (field(record, signPosition, signPosition)) {
            case "1" -> Money.zero(currency).subtract(amount);
            case "2" -> amount;
            default -> throw malformed("The sign of the " + name + " must be 1 (debit) or 2 (credit)");
        };
    }

    private static Direction direction(String code) {
        return switch (code) {
            case "1" -> Direction.DEBIT;
            case "2" -> Direction.CREDIT;
            default -> throw malformed("The debit/credit code must be 1 (debit) or 2 (credit)");
        };
    }

    private static LocalDate date(String record, int from, int to, String name) {
        try {
            return LocalDate.parse(field(record, from, to), DATE);
        } catch (DateTimeParseException e) {
            throw malformed("The " + name + " (positions " + from + "-" + to + ") is not a valid YYMMDD date", e);
        }
    }

    private static Currency currency(String numericCode) {
        Currency currency = numericCode.chars().allMatch(c -> c >= '0' && c <= '9')
                ? CURRENCIES_BY_NUMBER.get(Integer.parseInt(numericCode))
                : null;
        if (currency == null || currency.getDefaultFractionDigits() != 2) {
            throw malformed("Currency code '" + numericCode + "' is not a supported ISO 4217 numeric code");
        }
        return currency;
    }

    private static InvalidStatementException malformed(String message) {
        return new InvalidStatementException(Reason.MALFORMED_FILE, message);
    }

    private static InvalidStatementException malformed(String message, Throwable cause) {
        return new InvalidStatementException(Reason.MALFORMED_FILE, message, cause);
    }

    private static final class AccountBuilder {
        private final String bank;
        private final String branch;
        private final String number;
        private final LocalDate startDate;
        private final LocalDate endDate;
        private final Money opening;
        private final Currency currency;
        private final List<StatementEntry> entries = new ArrayList<>();

        AccountBuilder(String bank, String branch, String number, LocalDate startDate, LocalDate endDate,
                Money opening, Currency currency) {
            this.bank = bank;
            this.branch = branch;
            this.number = number;
            this.startDate = startDate;
            this.endDate = endDate;
            this.opening = opening;
            this.currency = currency;
        }

        /** Record 33 repeats counts and totals; a misread amount shows up here before the balance check (B11, B18). */
        void requireTotals(int debitCount, Money debitTotal, int creditCount, Money creditTotal) {
            Money debits = Money.zero(currency);
            Money credits = Money.zero(currency);
            int debitsFound = 0;
            for (StatementEntry movement : entries) {
                if (movement.direction() == Direction.DEBIT) {
                    debits = debits.add(movement.amount());
                    debitsFound++;
                } else {
                    credits = credits.add(movement.amount());
                }
            }
            int creditsFound = entries.size() - debitsFound;
            if (debitCount != debitsFound || !debitTotal.equals(debits)
                    || creditCount != creditsFound || !creditTotal.equals(credits)) {
                throw new InvalidStatementException(Reason.UNBALANCED,
                        "Record 33 declares " + debitCount + " debits of " + debitTotal + " and " + creditCount
                                + " credits of " + creditTotal + ", but the movements are " + debitsFound
                                + " debits of " + debits + " and " + creditsFound + " credits of " + credits);
            }
        }

        Statement build(Money closing) {
            Iban iban = SpanishAccount.toIban(bank, branch, number);
            return new Statement(iban, null, new Balance(opening, startDate), new Balance(closing, endDate), entries);
        }
    }

    private static final class EntryBuilder {
        private final LocalDate operationDate;
        private final LocalDate valueDate;
        private final Direction direction;
        private final Money amount;
        private final String documentNumber;
        private final String reference1;
        private final String reference2;
        private final List<String> concepts = new ArrayList<>();

        EntryBuilder(LocalDate operationDate, LocalDate valueDate, Direction direction, Money amount,
                String documentNumber, String reference1, String reference2) {
            this.operationDate = operationDate;
            this.valueDate = valueDate;
            this.direction = direction;
            this.amount = amount;
            this.documentNumber = documentNumber;
            this.reference1 = reference1;
            this.reference2 = reference2;
        }

        /**
         * Reference 2 usually carries the invoice or payment reference; reference 1 is the payer's own
         * reference and the document number is assigned by the bank. Fields made only of zeros are empty.
         */
        StatementEntry build() {
            String remittance = concepts.stream().map(String::strip).filter(c -> !c.isEmpty())
                    .collect(Collectors.joining(" "));
            TransactionDetail detail = new TransactionDetail(null, PaymentReference.parse(emptyIfZeros(reference2)),
                    remittance, null, emptyIfZeros(reference1), emptyIfZeros(documentNumber));
            return new StatementEntry(amount, direction, operationDate, valueDate, null, false, List.of(detail));
        }

        private static String emptyIfZeros(String value) {
            return value.strip().matches("0*") ? null : value;
        }
    }
}
