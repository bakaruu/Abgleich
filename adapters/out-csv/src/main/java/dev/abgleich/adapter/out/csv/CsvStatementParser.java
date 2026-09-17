package dev.abgleich.adapter.out.csv;

import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.application.statement.port.out.ParsedStatementFile;
import dev.abgleich.application.statement.port.out.StatementParserPort;
import dev.abgleich.application.statement.port.out.StatementSniff;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.InvalidReferenceException;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Reads the Abgleich CSV format, for banks and tools that export neither camt nor Norma 43:
 *
 * <pre>
 * #abgleich-csv;version=1;account=CH93...;currency=CHF;opening=2500.00;opening_date=2026-09-14;closing=4648.00;closing_date=2026-09-15
 * booking_date;value_date;direction;amount;reference;remittance_text;counterparty_name;end_to_end_id;bank_reference;charges
 * 2026-09-15;2026-09-15;CREDIT;480.00;RF18539007547034;;Alpenblick Hotel SA;;CSV-0001;
 * </pre>
 *
 * <p>The first line carries the account and its balances, so a CSV is validated like any statement
 * (B11). UTF-8 is required and checked, a byte order mark is accepted (B19). Amounts use a dot and at
 * most two decimals; "1.815,00" is rejected instead of being read as 1.815 (B18).
 */
public final class CsvStatementParser implements StatementParserPort {

    static final String MARKER = "#abgleich-csv";
    private static final Pattern SNIFF = Pattern.compile("^(ï»¿)?" + Pattern.quote(MARKER) + ";");
    private static final Set<String> COLUMNS = Set.of("booking_date", "value_date", "direction", "amount", "reference",
            "remittance_text", "counterparty_name", "end_to_end_id", "bank_reference", "charges");
    private static final Pattern AMOUNT = Pattern.compile("\\d{1,15}(\\.\\d{1,2})?");
    /** No field of a bank statement is longer; a bigger one is garbage or an attack (B42). */
    static final int MAX_FIELD_LENGTH = 500;

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setDelimiter(';')
            .setQuote('"')
            .setIgnoreEmptyLines(true)
            .setTrim(true)
            .get();

    @Override
    public boolean canParse(StatementSniff sniff) {
        return sniff.matchesAsciiStart(SNIFF);
    }

    @Override
    public ParsedStatementFile parse(InputStream in) {
        Objects.requireNonNull(in, "in");
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)));
        try {
            Header header = header(reader.readLine());
            // The parser is not closed: closing it would close the caller's stream.
            CSVParser csv = CSVParser.builder().setReader(reader).setFormat(FORMAT).get();
            List<StatementEntry> entries = new ArrayList<>();
            Map<String, Integer> columns = null;
            for (CSVRecord record : csv) {
                if (columns == null) {
                    columns = columns(record);
                    continue;
                }
                entries.add(entry(record, columns, header.currency()));
            }
            if (columns == null) {
                throw malformed("Line 2: the column header is missing");
            }
            Statement statement = new Statement(header.account(), null, header.opening(), header.closing(), entries);
            return new ParsedStatementFile(StatementFormat.CSV, null, List.of(statement));
        } catch (CharacterCodingException e) {
            throw malformed("The CSV file is not valid UTF-8 text", e);
        } catch (IOException | UncheckedIOException e) {
            throw malformed("The CSV file could not be read or its quotes are not closed", e);
        } catch (IllegalStateException e) {
            // Commons CSV reports inconsistent quoting this way.
            throw malformed("The CSV file has a malformed quoted field", e);
        }
    }

    private static Header header(String line) {
        if (line == null) {
            throw malformed("The CSV file is empty");
        }
        String first = line.startsWith("﻿") ? line.substring(1) : line;
        String[] parts = first.split(";", -1);
        if (!parts[0].equals(MARKER)) {
            throw malformed("Line 1 must start with " + MARKER);
        }
        Map<String, String> values = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals < 1) {
                throw malformed("Line 1: '" + limited(parts[i]) + "' is not a name=value pair");
            }
            values.put(parts[i].substring(0, equals).strip(), parts[i].substring(equals + 1).strip());
        }
        if (!"1".equals(values.get("version"))) {
            throw new InvalidStatementException(Reason.UNSUPPORTED_VERSION, "Only Abgleich CSV version 1 is supported");
        }
        Currency currency = currency(required(values, "currency"));
        Iban account;
        try {
            account = Iban.of(required(values, "account"));
        } catch (InvalidReferenceException e) {
            throw malformed("Line 1: the account IBAN is invalid", e);
        }
        return new Header(account, currency,
                new Balance(signedAmount(required(values, "opening"), currency, "Line 1: opening"),
                        date(required(values, "opening_date"), "Line 1: opening_date")),
                new Balance(signedAmount(required(values, "closing"), currency, "Line 1: closing"),
                        date(required(values, "closing_date"), "Line 1: closing_date")));
    }

    private static Map<String, Integer> columns(CSVRecord record) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < record.size(); i++) {
            columns.put(record.get(i).toLowerCase(Locale.ROOT), i);
        }
        if (!columns.keySet().equals(COLUMNS)) {
            throw malformed("Line 2: the columns must be exactly " + String.join(";", COLUMNS.stream().sorted().toList()));
        }
        return columns;
    }

    private static StatementEntry entry(CSVRecord record, Map<String, Integer> columns, Currency currency) {
        String line = "Line " + (record.getRecordNumber() + 1);
        if (record.size() != COLUMNS.size()) {
            throw malformed(line + ": expected " + COLUMNS.size() + " fields but found " + record.size());
        }
        try {
            Direction direction = switch (field(record, columns, "direction").toUpperCase(Locale.ROOT)) {
                case "CREDIT", "CRDT" -> Direction.CREDIT;
                case "DEBIT", "DBIT" -> Direction.DEBIT;
                default -> throw malformed("direction must be CREDIT or DEBIT");
            };
            Money amount = unsignedAmount(field(record, columns, "amount"), currency, "amount");
            String charges = field(record, columns, "charges");
            TransactionDetail detail = new TransactionDetail(null,
                    PaymentReference.parse(field(record, columns, "reference")),
                    field(record, columns, "remittance_text"),
                    field(record, columns, "counterparty_name"),
                    field(record, columns, "end_to_end_id"),
                    null,
                    charges.isEmpty() ? null : unsignedAmount(charges, currency, "charges"));
            String bankReference = field(record, columns, "bank_reference");
            return new StatementEntry(amount, direction, date(field(record, columns, "booking_date"), "booking_date"),
                    date(field(record, columns, "value_date"), "value_date"), bankReference, false, List.of(detail));
        } catch (InvalidStatementException e) {
            throw new InvalidStatementException(e.reason(), line + ": " + e.getMessage(), e);
        } catch (InvalidReferenceException e) {
            throw malformed(line + ": the reference is longer than 35 characters", e);
        }
    }

    private static String field(CSVRecord record, Map<String, Integer> columns, String name) {
        String value = record.get(columns.get(name));
        if (value.length() > MAX_FIELD_LENGTH) {
            throw new InvalidStatementException(Reason.FORBIDDEN_CONTENT,
                    name + " is longer than " + MAX_FIELD_LENGTH + " characters");
        }
        return value;
    }

    private static String required(Map<String, String> values, String name) {
        String value = values.get(name);
        if (value == null || value.isEmpty()) {
            throw malformed("Line 1: '" + name + "' is required");
        }
        return value;
    }

    private static Money unsignedAmount(String text, Currency currency, String name) {
        if (!AMOUNT.matcher(text).matches()) {
            throw malformed(name + " must be a positive number with a dot and at most two decimals, like 1815.00");
        }
        return new Money(new BigDecimal(text), currency);
    }

    private static Money signedAmount(String text, Currency currency, String name) {
        boolean negative = text.startsWith("-");
        Money amount = unsignedAmount(negative ? text.substring(1) : text, currency, name);
        return negative ? Money.zero(currency).subtract(amount) : amount;
    }

    private static LocalDate date(String text, String name) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw malformed(name + " must be a date like 2026-09-15", e);
        }
    }

    private static Currency currency(String code) {
        try {
            Currency currency = Currency.getInstance(code.toUpperCase(Locale.ROOT));
            if (currency.getDefaultFractionDigits() != 2) {
                throw malformed("Line 1: currency " + code + " is not supported");
            }
            return currency;
        } catch (IllegalArgumentException e) {
            throw malformed("Line 1: '" + limited(code) + "' is not an ISO 4217 currency code", e);
        }
    }

    private static String limited(String text) {
        return text.length() <= 40 ? text : text.substring(0, 40) + "...";
    }

    private static InvalidStatementException malformed(String message) {
        return new InvalidStatementException(Reason.MALFORMED_FILE, message);
    }

    private static InvalidStatementException malformed(String message, Throwable cause) {
        return new InvalidStatementException(Reason.MALFORMED_FILE, message, cause);
    }

    private record Header(Iban account, Currency currency, Balance opening, Balance closing) {
    }
}
