package dev.abgleich.adapter.out.camt;

import dev.abgleich.application.port.out.ParsedStatementFile;
import dev.abgleich.application.port.out.StatementFormat;
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
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Currency;
import java.util.Deque;
import java.util.List;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Walks one camt.053 document event by event. Single use: create one per file.
 *
 * <p>Elements are identified by their path from the root, so an {@code Amt} inside a balance is
 * never confused with the {@code Amt} of an entry or of a transaction.
 */
final class Camt053DocumentReader {

    /** Real statements nest about 12 levels; anything much deeper is an attack or garbage (B42). */
    static final int MAX_DEPTH = 32;
    /** The longest camt text field is 500 characters; this leaves room without risking memory (B42). */
    static final int MAX_TEXT_LENGTH = 4096;

    private static final String MSG_ID = "/Document/BkToCstmrStmt/GrpHdr/MsgId";
    private static final String STMT = "/Document/BkToCstmrStmt/Stmt";
    private static final String STMT_ID = STMT + "/Id";
    private static final String STMT_IBAN = STMT + "/Acct/Id/IBAN";
    private static final String STMT_OTHER_ACCOUNT = STMT + "/Acct/Id/Othr";
    private static final String BAL = STMT + "/Bal";
    private static final String BAL_TYPE = BAL + "/Tp/CdOrPrtry/Cd";
    private static final String BAL_AMOUNT = BAL + "/Amt";
    private static final String BAL_INDICATOR = BAL + "/CdtDbtInd";
    private static final String BAL_DATE = BAL + "/Dt/Dt";
    private static final String BAL_DATE_TIME = BAL + "/Dt/DtTm";
    private static final String NTRY = STMT + "/Ntry";
    private static final String NTRY_AMOUNT = NTRY + "/Amt";
    private static final String NTRY_INDICATOR = NTRY + "/CdtDbtInd";
    private static final String NTRY_REVERSAL = NTRY + "/RvslInd";
    private static final String NTRY_STATUS = NTRY + "/Sts";
    private static final String NTRY_BOOKING_DATE = NTRY + "/BookgDt/Dt";
    private static final String NTRY_BOOKING_DATE_TIME = NTRY + "/BookgDt/DtTm";
    private static final String NTRY_VALUE_DATE = NTRY + "/ValDt/Dt";
    private static final String NTRY_VALUE_DATE_TIME = NTRY + "/ValDt/DtTm";
    private static final String NTRY_BANK_REF = NTRY + "/AcctSvcrRef";
    private static final String TX = NTRY + "/NtryDtls/TxDtls";
    private static final String TX_BANK_REF = TX + "/Refs/AcctSvcrRef";
    private static final String TX_END_TO_END = TX + "/Refs/EndToEndId";
    private static final String TX_AMOUNT = TX + "/AmtDtls/TxAmt/Amt";
    private static final String TX_DEBTOR = TX + "/RltdPties/Dbtr/Nm";
    private static final String TX_CREDITOR = TX + "/RltdPties/Cdtr/Nm";
    private static final String TX_UNSTRUCTURED = TX + "/RmtInf/Ustrd";
    private static final String TX_REFERENCE = TX + "/RmtInf/Strd/CdtrRefInf/Ref";

    private final XMLStreamReader xml;
    private final StringBuilder path = new StringBuilder();
    private final Deque<Integer> pathLengths = new ArrayDeque<>();
    private final StringBuilder text = new StringBuilder();

    private String messageId;
    private final List<Statement> statements = new ArrayList<>();
    private StatementBuilder statement;
    private BalanceBuilder balance;
    private EntryBuilder entry;
    private DetailBuilder detail;
    private String amountCurrency;

    Camt053DocumentReader(XMLStreamReader xml) {
        this.xml = xml;
    }

    ParsedStatementFile read() throws XMLStreamException {
        while (xml.hasNext()) {
            switch (xml.next()) {
                case XMLStreamConstants.DTD, XMLStreamConstants.ENTITY_REFERENCE, XMLStreamConstants.ENTITY_DECLARATION ->
                        throw new InvalidStatementException(Reason.FORBIDDEN_CONTENT,
                                "Document type declarations and entities are not allowed in bank files");
                case XMLStreamConstants.START_ELEMENT -> startElement();
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> appendText();
                case XMLStreamConstants.END_ELEMENT -> endElement();
                default -> {
                    // Comments, processing instructions and the document end carry no data.
                }
            }
        }
        if (statements.isEmpty()) {
            throw malformed("The file contains no statement (Stmt)");
        }
        return new ParsedStatementFile(StatementFormat.CAMT053_V04, messageId, statements);
    }

    private void startElement() {
        if (pathLengths.size() >= MAX_DEPTH) {
            throw new InvalidStatementException(Reason.FORBIDDEN_CONTENT,
                    "XML elements are nested deeper than " + MAX_DEPTH + " levels");
        }
        if (pathLengths.isEmpty()) {
            requireSupportedRoot();
        }
        pathLengths.push(path.length());
        path.append('/').append(xml.getLocalName());
        text.setLength(0);

        switch (path.toString()) {
            case STMT -> statement = new StatementBuilder();
            case BAL -> balance = new BalanceBuilder();
            case NTRY -> entry = statement.newEntry();
            case TX -> detail = new DetailBuilder();
            default -> {
                // Other elements only matter when they end and their text is complete.
            }
        }
        if (xml.getLocalName().equals("Amt")) {
            amountCurrency = xml.getAttributeValue(null, "Ccy");
        }
    }

    private void requireSupportedRoot() {
        String namespace = xml.getNamespaceURI();
        if (!"Document".equals(xml.getLocalName()) || namespace == null
                || !namespace.startsWith(Camt053Parser.NAMESPACE_PREFIX)) {
            throw malformed("The file is not a camt.053 document");
        }
        if (!namespace.equals(Camt053Parser.NAMESPACE_V04)) {
            throw new InvalidStatementException(Reason.UNSUPPORTED_VERSION,
                    "camt.053 version " + namespace.substring(Camt053Parser.NAMESPACE_PREFIX.length() - "001.".length())
                            + " is not supported; supported version: 001.04");
        }
    }

    private void appendText() {
        int length = xml.getTextLength();
        if (text.length() + length > MAX_TEXT_LENGTH) {
            throw new InvalidStatementException(Reason.FORBIDDEN_CONTENT,
                    "An XML element holds more than " + MAX_TEXT_LENGTH + " characters of text");
        }
        text.append(xml.getTextCharacters(), xml.getTextStart(), length);
    }

    private void endElement() {
        String value = text.toString().strip();
        text.setLength(0);
        try {
            handleEnd(path.toString(), value);
        } catch (InvalidStatementException e) {
            if (entry == null) {
                throw e;
            }
            throw new InvalidStatementException(e.reason(), "Entry " + entry.position + ": " + e.getMessage(), e);
        }
        path.setLength(pathLengths.pop());
    }

    private void handleEnd(String elementPath, String value) {
        switch (elementPath) {
            case MSG_ID -> messageId = value;
            case STMT_ID -> statement.id = value;
            case STMT_IBAN -> statement.account = iban(value);
            case STMT_OTHER_ACCOUNT -> throw malformed("Only accounts identified by IBAN are supported");
            case BAL_TYPE -> balance.type = value;
            case BAL_AMOUNT -> balance.amount = money(value, "balance amount");
            case BAL_INDICATOR -> balance.direction = direction(value, "balance");
            case BAL_DATE -> balance.date = date(value, "balance date");
            case BAL_DATE_TIME -> balance.date = dateOfDateTime(value, "balance date");
            case BAL -> {
                statement.addBalance(balance);
                balance = null;
            }
            case NTRY_AMOUNT -> entry.amount = money(value, "entry amount");
            case NTRY_INDICATOR -> entry.direction = direction(value, "entry");
            case NTRY_REVERSAL -> entry.reversal = Boolean.parseBoolean(value);
            case NTRY_STATUS -> entry.status = value;
            case NTRY_BOOKING_DATE -> entry.bookingDate = date(value, "booking date");
            case NTRY_BOOKING_DATE_TIME -> entry.bookingDate = dateOfDateTime(value, "booking date");
            case NTRY_VALUE_DATE -> entry.valueDate = date(value, "value date");
            case NTRY_VALUE_DATE_TIME -> entry.valueDate = dateOfDateTime(value, "value date");
            case NTRY_BANK_REF -> entry.bankReference = value;
            case NTRY -> {
                statement.addEntry(entry);
                entry = null;
            }
            case TX_BANK_REF -> detail.bankReference = value;
            case TX_END_TO_END -> detail.endToEndId = "NOTPROVIDED".equals(value) ? null : value;
            case TX_AMOUNT -> detail.amount = money(value, "transaction amount");
            case TX_DEBTOR -> detail.debtorName = value;
            case TX_CREDITOR -> detail.creditorName = value;
            case TX_UNSTRUCTURED -> detail.addUnstructured(value);
            case TX_REFERENCE -> detail.reference = value;
            case TX -> {
                entry.details.add(detail);
                detail = null;
            }
            case STMT -> {
                statements.add(statement.build());
                statement = null;
            }
            default -> {
                // Elements Abgleich does not use: BkTxCd, TxsSummry, charges, addresses...
            }
        }
    }

    private Money money(String value, String field) {
        if (amountCurrency == null) {
            throw malformed("The " + field + " has no currency (Ccy)");
        }
        try {
            return new Money(new BigDecimal(value), Currency.getInstance(amountCurrency));
        } catch (IllegalArgumentException e) {
            throw malformed("The " + field + " is not a valid amount in a supported currency", e);
        }
    }

    private static Direction direction(String value, String field) {
        return switch (value) {
            case "CRDT" -> Direction.CREDIT;
            case "DBIT" -> Direction.DEBIT;
            default -> throw malformed("The " + field + " credit/debit indicator must be CRDT or DBIT");
        };
    }

    private static LocalDate date(String value, String field) {
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw malformed("The " + field + " is not a valid date", e);
        }
    }

    /** Keeps the calendar date as the bank wrote it; converting to UTC could move it a day (B15). */
    private static LocalDate dateOfDateTime(String value, String field) {
        try {
            return LocalDate.from(DateTimeFormatter.ISO_DATE_TIME.parse(value));
        } catch (DateTimeParseException e) {
            throw malformed("The " + field + " is not a valid date and time", e);
        }
    }

    private static Iban iban(String value) {
        try {
            return Iban.of(value);
        } catch (InvalidReferenceException e) {
            throw malformed("The account IBAN is invalid", e);
        }
    }

    private static InvalidStatementException malformed(String message) {
        return new InvalidStatementException(Reason.MALFORMED_FILE, message);
    }

    private static InvalidStatementException malformed(String message, Throwable cause) {
        return new InvalidStatementException(Reason.MALFORMED_FILE, message, cause);
    }

    private static final class StatementBuilder {
        private String id;
        private Iban account;
        private Balance opening;
        private boolean openingIsFinal;
        private Balance closing;
        private final List<StatementEntry> entries = new ArrayList<>();
        private int entryCount;

        EntryBuilder newEntry() {
            entryCount++;
            return new EntryBuilder(entryCount);
        }

        void addBalance(BalanceBuilder builder) {
            switch (builder.type == null ? "" : builder.type) {
                // OPBD is the opening booked balance; PRCD (previous closing) is its fallback.
                case "OPBD" -> {
                    opening = builder.build();
                    openingIsFinal = true;
                }
                case "PRCD" -> {
                    if (!openingIsFinal) {
                        opening = builder.build();
                    }
                }
                case "CLBD" -> closing = builder.build();
                default -> {
                    // Available and interim balances are not used for validation.
                }
            }
        }

        void addEntry(EntryBuilder builder) {
            if (builder.isBooked()) {
                entries.add(builder.build());
            }
        }

        Statement build() {
            if (account == null) {
                throw malformed("A statement has no account IBAN");
            }
            if (opening == null || closing == null) {
                throw malformed("A statement must have an opening (OPBD or PRCD) and a closing (CLBD) balance");
            }
            return new Statement(account, id, opening, closing, entries);
        }
    }

    private static final class BalanceBuilder {
        private String type;
        private Money amount;
        private Direction direction;
        private LocalDate date;

        Balance build() {
            if (amount == null || direction == null || date == null) {
                throw malformed("A balance must have an amount, a credit/debit indicator and a date");
            }
            Money signed = direction == Direction.CREDIT ? amount : Money.zero(amount.currency()).subtract(amount);
            return new Balance(signed, date);
        }
    }

    private static final class EntryBuilder {
        private final int position;
        private Money amount;
        private Direction direction;
        private boolean reversal;
        private String status;
        private LocalDate bookingDate;
        private LocalDate valueDate;
        private String bankReference;
        private final List<DetailBuilder> details = new ArrayList<>();

        EntryBuilder(int position) {
            this.position = position;
        }

        /** Pending (PDNG) and informational (INFO) entries are not part of the booked balance. */
        boolean isBooked() {
            if (status == null) {
                throw malformed("The entry has no status (Sts)");
            }
            return status.equals("BOOK");
        }

        StatementEntry build() {
            if (amount == null) {
                throw malformed("The entry has no amount");
            }
            if (direction == null) {
                throw malformed("The entry has no credit/debit indicator (CdtDbtInd)");
            }
            if (bookingDate == null) {
                throw malformed("The entry has no booking date");
            }
            List<TransactionDetail> built = details.stream().map(d -> d.build(direction)).toList();
            return new StatementEntry(amount, direction, bookingDate, valueDate, bankReference, reversal, built);
        }
    }

    private static final class DetailBuilder {
        private Money amount;
        private String reference;
        private final StringBuilder unstructured = new StringBuilder();
        private String debtorName;
        private String creditorName;
        private String endToEndId;
        private String bankReference;

        void addUnstructured(String value) {
            if (!unstructured.isEmpty()) {
                unstructured.append(' ');
            }
            unstructured.append(value);
        }

        /** The counterparty is whoever is on the other side: the payer of a credit, the payee of a debit. */
        TransactionDetail build(Direction direction) {
            String counterparty = direction == Direction.CREDIT ? debtorName : creditorName;
            PaymentReference parsedReference;
            try {
                parsedReference = PaymentReference.parse(reference);
            } catch (InvalidReferenceException e) {
                throw malformed("The creditor reference is longer than 35 characters", e);
            }
            return new TransactionDetail(amount, parsedReference, unstructured.toString(),
                    counterparty, endToEndId, bankReference);
        }
    }
}
