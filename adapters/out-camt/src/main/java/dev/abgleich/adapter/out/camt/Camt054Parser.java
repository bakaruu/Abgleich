package dev.abgleich.adapter.out.camt;

import dev.abgleich.application.statement.port.out.ParsedStatementFile;
import dev.abgleich.application.statement.port.out.StatementParserPort;
import dev.abgleich.application.statement.port.out.StatementSniff;
import java.io.InputStream;

/**
 * ISO 20022 camt.054 debit and credit notifications, versions 001.04 and 001.08. They carry the same
 * entries as the day's camt.053 but no balances, so they only enrich transactions (B12).
 */
public final class Camt054Parser implements StatementParserPort {

    static final String NAMESPACE_PREFIX = CamtMessage.NOTIFICATION.namespacePrefix();

    private final CamtParser parser = new CamtParser(CamtMessage.NOTIFICATION);

    @Override
    public boolean canParse(StatementSniff sniff) {
        return sniff.containsAscii(NAMESPACE_PREFIX);
    }

    @Override
    public ParsedStatementFile parse(InputStream in) {
        return parser.parse(in);
    }
}
