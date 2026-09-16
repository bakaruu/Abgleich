package dev.abgleich.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ImportResult.Outcome;
import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ImportStatementCommand;
import dev.abgleich.application.port.in.ImportedStatement;
import dev.abgleich.application.port.out.DuplicateImportException;
import dev.abgleich.application.port.out.NewStatementImport;
import dev.abgleich.application.port.out.ParsedStatementFile;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.application.port.out.StatementImportRepositoryPort;
import dev.abgleich.application.port.out.StatementParserPort;
import dev.abgleich.application.port.out.StatementSniff;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import dev.abgleich.domain.statement.Statement;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImportStatementServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T20:05:00Z");
    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final String FILE = "FAKE-STATEMENT\nsome content after the part the parser reads";

    private final FakeRepository repository = new FakeRepository();
    private final ImportStatementService service = new ImportStatementService(
            List.of(new FakeParser("OTHER-FORMAT"), new FakeParser("FAKE-STATEMENT")),
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void stores_each_statement_with_the_fingerprint_of_the_whole_file() throws NoSuchAlgorithmException {
        ImportResult result = service.importStatement(command(FILE));

        assertThat(result.outcome()).isEqualTo(Outcome.IMPORTED);
        assertThat(repository.stored).singleElement().satisfies(stored -> {
            assertThat(stored.fileSha256()).isEqualTo(sha256(FILE));
            assertThat(stored.source()).isEqualTo(ImportSource.REST);
            assertThat(stored.messageId()).isEqualTo("MSG-1");
            assertThat(stored.statement().account()).isEqualTo(ACCOUNT);
        });
    }

    @Test
    void B38_received_time_comes_from_the_injected_clock() {
        service.importStatement(command(FILE));

        assertThat(repository.stored.getFirst().receivedAt()).isEqualTo(NOW);
    }

    @Test
    void B21_duplicate_refused_by_the_database_returns_the_previous_import() {
        repository.refuseAsDuplicate = true;

        ImportResult result = service.importStatement(command(FILE));

        assertThat(result.outcome()).isEqualTo(Outcome.ALREADY_IMPORTED);
        assertThat(result.statements()).isEqualTo(repository.previous);
        assertThat(repository.previousLookedUpWithAccounts).containsExactly(ACCOUNT);
    }

    @Test
    void unknown_format_is_rejected_before_anything_is_stored() {
        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> service.importStatement(command("%PDF-1.7 not a statement")));

        assertThat(rejected.reason()).isEqualTo(Reason.UNKNOWN_FORMAT);
        assertThat(repository.stored).isEmpty();
    }

    @Test
    void B42_file_larger_than_the_limit_is_rejected_while_reading() {
        InputStream endless = new InputStream() {
            private final byte[] header = "FAKE-STATEMENT\n".getBytes(StandardCharsets.US_ASCII);
            private int position;

            @Override
            public int read() {
                return position < header.length ? header[position++] : 'x';
            }
        };

        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> service.importStatement(new ImportStatementCommand(ImportSource.WEB, () -> endless)));

        assertThat(rejected.reason()).isEqualTo(Reason.FORBIDDEN_CONTENT);
        assertThat(rejected).hasMessage("The file is larger than 20 MB");
    }

    @Test
    void B39_unreadable_upload_is_translated_into_a_domain_exception() {
        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> service.importStatement(new ImportStatementCommand(ImportSource.WEB, () -> {
                    throw new IOException("connection reset");
                })));

        assertThat(rejected.reason()).isEqualTo(Reason.MALFORMED_FILE);
        assertThat(rejected).hasCauseInstanceOf(IOException.class);
    }

    private static ImportStatementCommand command(String content) {
        return new ImportStatementCommand(ImportSource.REST,
                () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256(String content) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** Recognizes files starting with its marker and reads only the first line, like a real parser that stops early. */
    private record FakeParser(String marker) implements StatementParserPort {

        @Override
        public boolean canParse(StatementSniff sniff) {
            return sniff.containsAscii(marker);
        }

        @Override
        public ParsedStatementFile parse(InputStream in) {
            try {
                while (in.read() != '\n') {
                    // consume the first line only
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            LocalDate day = LocalDate.of(2026, 9, 15);
            Statement statement = new Statement(ACCOUNT, "STMT-1",
                    new Balance(Money.chf("0.00"), day), new Balance(Money.chf("0.00"), day), List.of());
            return new ParsedStatementFile(StatementFormat.CAMT053_V04, "MSG-1", List.of(statement));
        }
    }

    private static final class FakeRepository implements StatementImportRepositoryPort {

        private final List<NewStatementImport> stored = new ArrayList<>();
        private boolean refuseAsDuplicate;
        private List<Iban> previousLookedUpWithAccounts;
        private final List<ImportedStatement> previous = List.of(new ImportedStatement(
                java.util.UUID.randomUUID(), ACCOUNT,
                new Balance(Money.chf("0.00"), LocalDate.of(2026, 9, 15)),
                new Balance(Money.chf("0.00"), LocalDate.of(2026, 9, 15)), 7, 0));

        @Override
        public List<ImportedStatement> store(List<NewStatementImport> imports) {
            if (refuseAsDuplicate) {
                throw new DuplicateImportException("uq_import_file", null);
            }
            stored.addAll(imports);
            return imports.stream().map(i -> new ImportedStatement(i.id(), i.statement().account(),
                    i.statement().openingBalance(), i.statement().closingBalance(), 0, 0)).toList();
        }

        @Override
        public List<ImportedStatement> findPrevious(String fileSha256, String messageId, List<Iban> accounts) {
            previousLookedUpWithAccounts = accounts;
            return previous;
        }

        @Override
        public List<dev.abgleich.application.port.in.EnrichedNotification> enrich(
                List<dev.abgleich.domain.statement.Notification> notifications) {
            return List.of();
        }
    }
}
