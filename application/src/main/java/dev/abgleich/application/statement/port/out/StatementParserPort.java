package dev.abgleich.application.statement.port.out;

import dev.abgleich.domain.statement.InvalidStatementException;
import java.io.InputStream;

/**
 * Reads one bank statement format into the format-neutral domain model.
 *
 * <p>Implementations detect their format by content, never by file name, and throw only
 * {@link InvalidStatementException} (B39).
 */
public interface StatementParserPort {

    boolean canParse(StatementSniff sniff);

    /**
     * Parses the whole file. The caller owns the stream and closes it.
     *
     * @throws InvalidStatementException if the file must be rejected; no statement is returned
     *     partially (B11)
     */
    ParsedStatementFile parse(InputStream in);
}
