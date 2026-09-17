package dev.abgleich.adapter.out.camt;

import dev.abgleich.application.statement.port.out.ParsedStatementFile;
import dev.abgleich.application.statement.port.out.StatementParserPort;
import dev.abgleich.application.statement.port.out.StatementSniff;
import java.io.InputStream;

/** ISO 20022 camt.053 bank-to-customer statements, versions 001.04 and 001.08 (Swiss Payment Standards, SEPA). */
public final class Camt053Parser implements StatementParserPort {

    static final String NAMESPACE_PREFIX = CamtMessage.STATEMENT.namespacePrefix();
    static final String NAMESPACE_V04 = NAMESPACE_PREFIX + "04";
    static final String NAMESPACE_V08 = NAMESPACE_PREFIX + "08";

    private final CamtParser parser = new CamtParser(CamtMessage.STATEMENT);

    @Override
    public boolean canParse(StatementSniff sniff) {
        return sniff.containsAscii(NAMESPACE_PREFIX);
    }

    @Override
    public ParsedStatementFile parse(InputStream in) {
        return parser.parse(in);
    }
}
