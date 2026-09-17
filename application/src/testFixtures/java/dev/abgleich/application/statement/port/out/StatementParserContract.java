package dev.abgleich.application.statement.port.out;

import dev.abgleich.application.statement.StatementFormat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.DeduplicationKey;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link StatementParserPort} must have, whatever the file format. An adapter test
 * extends this class and provides a parser and sample files; a new format cannot be added without
 * passing all of it.
 */
public abstract class StatementParserContract {

    /** Start of a file in each known format, to check that a parser only claims its own. */
    private static final Map<String, byte[]> FORMAT_SAMPLES = Map.of(
            "CAMT053", ascii("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.053.001.04\">"),
            "CAMT054", ascii("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                    + "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:camt.054.001.08\">"),
            "NORMA43", ascii("11210004180200051332260915260915200000000500000978"
                    + "3TALLER DEMO ABGLEICH SL      "),
            "CSV", ascii("#abgleich-csv;version=1;account=CH9300762011623852957;currency=CHF\n"),
            "PDF", ascii("%PDF-1.7\n%âãÏÓ\n"),
            "EMPTY", new byte[0]);

    protected abstract StatementParserPort parser();

    protected abstract StatementFormat format();

    /**
     * A valid file with at least one statement, at least one credit and one debit, and a batch entry
     * with several transactions if the format supports it.
     */
    protected abstract byte[] validFile();

    /**
     * {@link #validFile()} with the closing balance of its first statement one cent higher, or {@code null}
     * for a format without balances.
     */
    protected abstract byte[] validFileWithClosingBalanceOneCentHigher();

    /** Formats without balances (camt.054) only enrich transactions and skip the balance checks. */
    protected boolean hasBalances() {
        return true;
    }

    private static List<StatementEntry> entries(ParsedStatementFile file) {
        return java.util.stream.Stream.concat(
                file.statements().stream().flatMap(statement -> statement.entries().stream()),
                file.notifications().stream().flatMap(notification -> notification.entries().stream())).toList();
    }

    private static List<List<DeduplicationKey>> keys(ParsedStatementFile file) {
        return java.util.stream.Stream.concat(file.statements().stream().map(Statement::deduplicationKeys),
                file.notifications().stream().map(dev.abgleich.domain.statement.Notification::deduplicationKeys)).toList();
    }

    @Test
    void valid_file_is_parsed_into_its_format() {
        ParsedStatementFile file = parse(validFile());

        assertThat(file.format()).isEqualTo(format());
        assertThat(entries(file)).isNotEmpty();
        assertThat(entries(file)).extracting(StatementEntry::direction)
                .as("the sample file must contain credits and debits")
                .contains(Direction.CREDIT, Direction.DEBIT);
    }

    @Test
    void B09_transactions_always_add_up_to_their_entry() {
        assertThat(entries(parse(validFile()))).allSatisfy(this::assertTransactionsAddUp);
    }

    @Test
    void B10_every_entry_has_a_positive_amount_and_a_direction() {
        assertThat(entries(parse(validFile()))).allSatisfy(entry -> {
            assertThat(entry.direction()).isNotNull();
            assertThat(entry.amount().isPositive()).isTrue();
        });
    }

    @Test
    void B11_closing_balance_one_cent_off_rejects_the_whole_file() {
        org.junit.jupiter.api.Assumptions.assumeTrue(hasBalances(), "format without balances");
        InvalidStatementException rejected = reject(validFileWithClosingBalanceOneCentHigher());

        assertThat(rejected.reason()).isEqualTo(Reason.UNBALANCED);
    }

    @Test
    void B11_truncated_file_is_always_rejected() {
        byte[] valid = validFile();

        IntStream.rangeClosed(1, 9).map(tenth -> valid.length * tenth / 10).forEach(cut ->
                assertThat(reject(Arrays.copyOf(valid, cut)))
                        .as("file cut after %d of %d bytes", cut, valid.length)
                        .isNotNull());
    }

    @Test
    void B13_format_is_recognized_by_content_and_other_formats_are_not_claimed() {
        assertThat(parser().canParse(StatementSniff.of(validFile()))).isTrue();
        FORMAT_SAMPLES.forEach((name, sample) -> {
            if (!name.equals(format().name().replaceFirst("_V\\d+$", ""))) {
                assertThat(parser().canParse(StatementSniff.of(sample))).as("claims a %s file", name).isFalse();
            }
        });
    }

    @Test
    void B16_parsing_the_same_file_again_gives_the_same_deduplication_keys() {
        List<List<DeduplicationKey>> first = keys(parse(validFile()));
        List<List<DeduplicationKey>> second = keys(parse(validFile()));

        assertThat(second).isEqualTo(first);
        assertThat(first).allSatisfy(keys -> assertThat(keys).doesNotHaveDuplicates());
    }

    @Test
    void parser_leaves_the_stream_open_for_the_caller() {
        AtomicBoolean closed = new AtomicBoolean();
        InputStream in = new FilterInputStream(new ByteArrayInputStream(validFile())) {
            @Override
            public void close() throws IOException {
                closed.set(true);
                super.close();
            }
        };

        parser().parse(in);

        assertThat(closed).as("the caller hashes the rest of the file after parsing").isFalse();
    }

    @Test
    void B39_empty_file_is_rejected_with_a_domain_exception() {
        assertThat(reject(new byte[0]).reason()).isEqualTo(Reason.MALFORMED_FILE);
    }

    /** Whatever the bytes, a parser either returns consistent statements or rejects the file (B39). */
    @Property(tries = 300)
    void B39_arbitrary_bytes_never_raise_technical_exceptions(@ForAll("arbitraryBytes") byte[] bytes) {
        parseOrReject(bytes);
    }

    /** A single damaged byte anywhere in a valid file: accepted only if still consistent (B11, B39). */
    @Property(tries = 500)
    void B39_damaged_byte_never_raises_technical_exceptions(@ForAll("damagedFiles") byte[] damaged) {
        parseOrReject(damaged);
    }

    @Provide
    Arbitrary<byte[]> arbitraryBytes() {
        return Arbitraries.bytes().array(byte[].class).ofMaxSize(2_000);
    }

    @Provide
    Arbitrary<byte[]> damagedFiles() {
        byte[] valid = validFile();
        return Combinators.combine(Arbitraries.integers().between(0, valid.length - 1), Arbitraries.bytes())
                .as((position, value) -> {
                    byte[] copy = valid.clone();
                    copy[position] = value;
                    return copy;
                });
    }

    private void parseOrReject(byte[] bytes) {
        ParsedStatementFile file;
        try {
            file = parse(bytes);
        } catch (InvalidStatementException rejected) {
            assertThat(rejected.getMessage()).isNotBlank();
            return;
        }
        assertThat(file.statements().isEmpty()).isNotEqualTo(file.notifications().isEmpty());
        entries(file).forEach(this::assertTransactionsAddUp);
    }

    private void assertTransactionsAddUp(StatementEntry entry) {
        Money sum = Money.zero(entry.amount().currency());
        for (TransactionDetail detail : entry.details()) {
            assertThat(detail.amount()).isNotNull();
            sum = sum.add(detail.amount());
        }
        if (!entry.details().isEmpty()) {
            assertThat(sum).isEqualTo(entry.amount());
        }
    }

    private ParsedStatementFile parse(byte[] bytes) {
        return parser().parse(new ByteArrayInputStream(bytes));
    }

    private InvalidStatementException reject(byte[] bytes) {
        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class, () -> parse(bytes));
        assertThat(rejected).as("file should have been rejected").isNotNull();
        return rejected;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.ISO_8859_1);
    }
}
