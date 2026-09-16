package dev.abgleich.adapter.out.camt;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/** Builds small synthetic camt.053 documents for edge cases, so each test shows only what it varies. */
final class CamtXml {

    static final String IBAN = "CH9300762011623852957";

    private CamtXml() {
    }

    static InputStream stream(String xml) {
        return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
    }

    static String document(StatementXml... statements) {
        return document(Camt053Parser.NAMESPACE_V04, statements);
    }

    static String document(String namespace, StatementXml... statements) {
        StringBuilder body = new StringBuilder();
        for (StatementXml statement : statements) {
            body.append(statement.toXml());
        }
        return head(namespace) + body + TAIL;
    }

    static StatementXml statement() {
        return new StatementXml();
    }

    static EntryXml credit(String amount) {
        return new EntryXml(amount, "CRDT");
    }

    static EntryXml debit(String amount) {
        return new EntryXml(amount, "DBIT");
    }

    static EntryXml withoutIndicator(String amount) {
        return new EntryXml(amount, null);
    }

    static DetailXml detail() {
        return new DetailXml();
    }

    /**
     * A statement with {@code count} credits of 1.00, produced lazily while it is read, so the test
     * itself never holds the whole file in memory (B14).
     */
    static InputStream largeStatement(int count) {
        String statementHead = "<Stmt><Id>LARGE</Id><Acct><Id><IBAN>" + IBAN + "</IBAN></Id></Acct>"
                + balance("OPBD", "0.00") + balance("CLBD", count + ".00");
        String entry = credit("1.00").detail(detail().reference("RF18539007547034")).toXml();
        Enumeration<InputStream> parts = new Enumeration<>() {
            private int produced = -1;

            @Override
            public boolean hasMoreElements() {
                return produced <= count;
            }

            @Override
            public InputStream nextElement() {
                produced++;
                if (produced == 0) {
                    return stream(head(Camt053Parser.NAMESPACE_V04) + statementHead);
                }
                return stream(produced <= count ? entry : "</Stmt>" + TAIL);
            }
        };
        return new SequenceInputStream(parts);
    }

    private static String head(String namespace) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="%s"><BkToCstmrStmt><GrpHdr><MsgId>MSG-1</MsgId></GrpHdr>
                """.formatted(namespace);
    }

    private static final String TAIL = "</BkToCstmrStmt></Document>";

    private static String balance(String type, String signedAmount) {
        BigDecimal amount = new BigDecimal(signedAmount);
        return "<Bal><Tp><CdOrPrtry><Cd>" + type + "</Cd></CdOrPrtry></Tp>"
                + "<Amt Ccy=\"CHF\">" + amount.abs().toPlainString() + "</Amt>"
                + "<CdtDbtInd>" + (amount.signum() < 0 ? "DBIT" : "CRDT") + "</CdtDbtInd>"
                + "<Dt><Dt>2026-09-15</Dt></Dt></Bal>";
    }

    static final class StatementXml {
        private String opening = "0.00";
        private String closing;
        private final List<EntryXml> entries = new ArrayList<>();

        StatementXml opening(String amount) {
            opening = amount;
            return this;
        }

        /** Without an explicit closing balance, the builder computes the one that makes the file balance. */
        StatementXml closing(String amount) {
            closing = amount;
            return this;
        }

        StatementXml entry(EntryXml entry) {
            entries.add(entry);
            return this;
        }

        String toXml() {
            BigDecimal computed = new BigDecimal(opening);
            StringBuilder body = new StringBuilder();
            for (EntryXml entry : entries) {
                body.append(entry.toXml());
                if (entry.status.equals("BOOK")) {
                    BigDecimal amount = new BigDecimal(entry.amount);
                    computed = "DBIT".equals(entry.indicator) ? computed.subtract(amount) : computed.add(amount);
                }
            }
            return "<Stmt><Id>STMT-1</Id><Acct><Id><IBAN>" + IBAN + "</IBAN></Id><Ccy>CHF</Ccy></Acct>"
                    + balance("OPBD", opening)
                    + balance("CLBD", closing != null ? closing : computed.toPlainString())
                    + body + "</Stmt>";
        }
    }

    static final class EntryXml {
        private final String amount;
        private final String indicator;
        private String status = "BOOK";
        private String bookingDate = "<Dt>2026-09-15</Dt>";
        private String valueDate;
        private boolean reversal;
        private final List<DetailXml> details = new ArrayList<>();

        private EntryXml(String amount, String indicator) {
            this.amount = amount;
            this.indicator = indicator;
        }

        EntryXml status(String value) {
            status = value;
            return this;
        }

        EntryXml bookingDateTime(String value) {
            bookingDate = "<DtTm>" + value + "</DtTm>";
            return this;
        }

        EntryXml valueDateTime(String value) {
            valueDate = "<DtTm>" + value + "</DtTm>";
            return this;
        }

        EntryXml reversal() {
            reversal = true;
            return this;
        }

        EntryXml detail(DetailXml detail) {
            details.add(detail);
            return this;
        }

        String toXml() {
            StringBuilder xml = new StringBuilder("<Ntry><Amt Ccy=\"CHF\">").append(amount).append("</Amt>");
            if (indicator != null) {
                xml.append("<CdtDbtInd>").append(indicator).append("</CdtDbtInd>");
            }
            if (reversal) {
                xml.append("<RvslInd>true</RvslInd>");
            }
            xml.append("<Sts>").append(status).append("</Sts>")
                    .append("<BookgDt>").append(bookingDate).append("</BookgDt>");
            if (valueDate != null) {
                xml.append("<ValDt>").append(valueDate).append("</ValDt>");
            }
            if (!details.isEmpty()) {
                xml.append("<NtryDtls>");
                details.forEach(d -> xml.append(d.toXml()));
                xml.append("</NtryDtls>");
            }
            return xml.append("</Ntry>").toString();
        }
    }

    static final class DetailXml {
        private String amount;
        private String reference;
        private String debtor;
        private String creditor;

        DetailXml amount(String value) {
            amount = value;
            return this;
        }

        DetailXml reference(String value) {
            reference = value;
            return this;
        }

        DetailXml debtor(String value) {
            debtor = value;
            return this;
        }

        DetailXml creditor(String value) {
            creditor = value;
            return this;
        }

        String toXml() {
            StringBuilder xml = new StringBuilder("<TxDtls>");
            if (amount != null) {
                xml.append("<AmtDtls><TxAmt><Amt Ccy=\"CHF\">").append(amount).append("</Amt></TxAmt></AmtDtls>");
            }
            if (debtor != null || creditor != null) {
                xml.append("<RltdPties>");
                if (debtor != null) {
                    xml.append("<Dbtr><Nm>").append(debtor).append("</Nm></Dbtr>");
                }
                if (creditor != null) {
                    xml.append("<Cdtr><Nm>").append(creditor).append("</Nm></Cdtr>");
                }
                xml.append("</RltdPties>");
            }
            if (reference != null) {
                xml.append("<RmtInf><Strd><CdtrRefInf><Ref>").append(reference).append("</Ref></CdtrRefInf></Strd></RmtInf>");
            }
            return xml.append("</TxDtls>").toString();
        }
    }
}
