package dev.abgleich.adapter.out.norma43;

import dev.abgleich.application.statement.port.out.ParsedStatementFile;
import dev.abgleich.application.statement.port.out.StatementParserPort;
import dev.abgleich.application.statement.port.out.StatementSniff;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Parses Spanish Norma 43 statements (AEB cuaderno 43): 80-character records 11 (account header),
 * 22 (movement), 23 (concept), 24 (currency equivalence), 33 (account totals) and 88 (end of file).
 *
 * <p>The charset is explicit and defaults to ISO-8859-1, which most Spanish banks use (B19).
 * Bytes that are not valid in the configured charset reject the file instead of being silently
 * replaced.
 */
public final class Norma43Parser implements StatementParserPort {

    /** Record 11 starts with bank, branch and account numbers, two dates and a balance sign. */
    private static final Pattern ACCOUNT_HEADER = Pattern.compile("^11\\d{30}[12]\\d{17}");

    private final Charset charset;

    public Norma43Parser() {
        this(StandardCharsets.ISO_8859_1);
    }

    public Norma43Parser(Charset charset) {
        this.charset = Objects.requireNonNull(charset, "charset");
    }

    @Override
    public boolean canParse(StatementSniff sniff) {
        return sniff.matchesAsciiStart(ACCOUNT_HEADER);
    }

    @Override
    public ParsedStatementFile parse(InputStream in) {
        Objects.requireNonNull(in, "in");
        Reader reader = new InputStreamReader(in, charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT));
        try {
            return new Norma43FileReader(new BoundedLineReader(reader)).read();
        } catch (CharacterCodingException e) {
            throw new InvalidStatementException(Reason.MALFORMED_FILE,
                    "The Norma 43 file is not valid " + charset.name() + " text", e);
        } catch (IOException e) {
            throw new InvalidStatementException(Reason.MALFORMED_FILE, "The Norma 43 file could not be read", e);
        }
    }
}
