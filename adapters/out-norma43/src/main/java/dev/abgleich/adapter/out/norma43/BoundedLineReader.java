package dev.abgleich.adapter.out.norma43;

import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;

/**
 * Reads lines ending in LF or CRLF (B20) without ever buffering more than {@link #MAX_LINE_LENGTH}
 * characters, so a file without line breaks cannot exhaust memory (B42).
 */
final class BoundedLineReader {

    static final int MAX_LINE_LENGTH = 256;

    private final BufferedReader reader;
    private final StringBuilder line = new StringBuilder(MAX_LINE_LENGTH);
    private int lineNumber;

    BoundedLineReader(Reader reader) {
        this.reader = new BufferedReader(reader);
    }

    /** The next line without its terminator, or {@code null} at the end of the file. */
    String next() throws IOException {
        line.setLength(0);
        int c = reader.read();
        if (c == -1) {
            return null;
        }
        lineNumber++;
        while (c != -1 && c != '\n') {
            if (line.length() == MAX_LINE_LENGTH) {
                throw new InvalidStatementException(Reason.FORBIDDEN_CONTENT,
                        "Line " + lineNumber + " is longer than " + MAX_LINE_LENGTH + " characters");
            }
            line.append((char) c);
            c = reader.read();
        }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            line.setLength(line.length() - 1);
        }
        return line.toString();
    }

    int lineNumber() {
        return lineNumber;
    }
}
