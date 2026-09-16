package dev.abgleich.application.port.out;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/** The first bytes of an uploaded file, used to detect its format by content. */
public final class StatementSniff {

    public static final int MAX_BYTES = 4096;

    private final byte[] head;

    private StatementSniff(byte[] head) {
        this.head = head;
    }

    public static StatementSniff of(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return new StatementSniff(Arrays.copyOf(bytes, Math.min(bytes.length, MAX_BYTES)));
    }

    /**
     * Whether the head contains an ASCII marker such as an XML namespace. The bytes are decoded as
     * ISO-8859-1, which maps every byte to one character, so the check works for UTF-8 and
     * Latin-1 files alike.
     */
    public boolean containsAscii(String marker) {
        return text().contains(marker);
    }

    /** Whether the file starts with the pattern, for formats recognized by their first record. */
    public boolean matchesAsciiStart(Pattern pattern) {
        return pattern.matcher(text()).lookingAt();
    }

    private String text() {
        return new String(head, StandardCharsets.ISO_8859_1);
    }
}
