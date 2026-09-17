package dev.abgleich.adapter.out.camt;

import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;

/** The two camt messages Abgleich reads, their namespaces, supported versions and container elements. */
enum CamtMessage {

    /** camt.053: end-of-day statement with balances. */
    STATEMENT("camt.053", "BkToCstmrStmt", "Stmt", StatementFormat.CAMT053_V04, StatementFormat.CAMT053_V08),
    /** camt.054: debit and credit notification, no balances. */
    NOTIFICATION("camt.054", "BkToCstmrDbtCdtNtfctn", "Ntfctn", StatementFormat.CAMT054_V04, StatementFormat.CAMT054_V08);

    static final String NAMESPACE_BASE = "urn:iso:std:iso:20022:tech:xsd:";

    private final String name;
    private final String messageElement;
    private final String accountElement;
    private final StatementFormat version04;
    private final StatementFormat version08;

    CamtMessage(String name, String messageElement, String accountElement, StatementFormat version04,
            StatementFormat version08) {
        this.name = name;
        this.messageElement = messageElement;
        this.accountElement = accountElement;
        this.version04 = version04;
        this.version08 = version08;
    }

    /** "urn:iso:std:iso:20022:tech:xsd:camt.053.001." */
    String namespacePrefix() {
        return NAMESPACE_BASE + name + ".001.";
    }

    /** Rejects anything but this message in a supported version, with a clear reason (B13). */
    StatementFormat formatFor(String rootElement, String namespace) {
        if (!"Document".equals(rootElement) || namespace == null || !namespace.startsWith(namespacePrefix())) {
            throw new InvalidStatementException(Reason.MALFORMED_FILE, "The file is not a " + name + " document");
        }
        String version = namespace.substring(namespacePrefix().length());
        return switch (version) {
            case "04" -> version04;
            case "08" -> version08;
            default -> throw new InvalidStatementException(Reason.UNSUPPORTED_VERSION,
                    name + " version 001." + version + " is not supported; supported versions: 001.04, 001.08");
        };
    }

    /** Maps the notification containers onto the statement ones so both share the same element paths. */
    String canonicalName(int depth, String localName) {
        if (depth == 2 && localName.equals(messageElement)) {
            return "BkToCstmrStmt";
        }
        if (depth == 3 && localName.equals(accountElement)) {
            return "Stmt";
        }
        return localName;
    }
}
