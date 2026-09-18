package dev.abgleich.application.statement.port.out;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * B14: the format is decided by the first bytes of a file, never by its name, and only the first bytes are ever
 * held in memory. A file that lies about its extension must still be read as what it is.
 */
class StatementSniffTest {

    private static final Pattern NORMA43_FIRST_RECORD = Pattern.compile("11\\d{8}");

    @Test
    void it_finds_a_marker_anywhere_in_the_head() {
        StatementSniff sniff = StatementSniff.of("<?xml version=\"1.0\"?><Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.04\">"
                .getBytes(StandardCharsets.UTF_8));

        assertThat(sniff.containsAscii("camt.053.001.04")).isTrue();
        assertThat(sniff.containsAscii("camt.054.001.04")).isFalse();
    }

    @Test
    void a_pattern_only_counts_at_the_very_start() {
        assertThat(StatementSniff.of("1120386001".getBytes(StandardCharsets.ISO_8859_1))
                .matchesAsciiStart(NORMA43_FIRST_RECORD)).isTrue();
        assertThat(StatementSniff.of("  1120386001".getBytes(StandardCharsets.ISO_8859_1))
                .matchesAsciiStart(NORMA43_FIRST_RECORD))
                .as("a record that starts two spaces in is not a Norma 43 header")
                .isFalse();
    }

    /** B19: Spanish files are Latin-1 and Swiss ones UTF-8; decoding byte by byte works for both. */
    @Test
    void accented_bytes_never_break_the_detection() {
        byte[] latin1 = "1120386001 PEÑA".getBytes(StandardCharsets.ISO_8859_1);
        byte[] utf8 = "<Document>PEÑA</Document>".getBytes(StandardCharsets.UTF_8);

        assertThat(StatementSniff.of(latin1).matchesAsciiStart(NORMA43_FIRST_RECORD)).isTrue();
        assertThat(StatementSniff.of(utf8).containsAscii("<Document>")).isTrue();
    }

    /** B42: a huge upload must not become a huge string just to find out what it is. */
    @Test
    void only_the_first_bytes_are_kept_however_large_the_file_is() {
        byte[] huge = new byte[StatementSniff.MAX_BYTES * 4];
        System.arraycopy("<Document>".getBytes(StandardCharsets.UTF_8), 0, huge, 0, 10);
        System.arraycopy("camt.053".getBytes(StandardCharsets.UTF_8), 0, huge, StatementSniff.MAX_BYTES + 100, 8);

        StatementSniff sniff = StatementSniff.of(huge);

        assertThat(sniff.containsAscii("<Document>")).isTrue();
        assertThat(sniff.containsAscii("camt.053")).as("past the first 4 KB nothing is read").isFalse();
    }

    @Test
    void an_empty_file_matches_nothing() {
        StatementSniff empty = StatementSniff.of(new byte[0]);

        assertThat(empty.containsAscii("<Document>")).isFalse();
        assertThat(empty.matchesAsciiStart(NORMA43_FIRST_RECORD)).isFalse();
    }
}
