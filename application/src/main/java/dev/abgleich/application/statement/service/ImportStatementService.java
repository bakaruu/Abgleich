package dev.abgleich.application.statement.service;

import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.application.statement.port.in.ImportResult;
import dev.abgleich.application.statement.port.in.ImportResult.Outcome;
import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ImportStatementUseCase;
import dev.abgleich.application.statement.port.out.DuplicateImportException;
import dev.abgleich.application.statement.port.out.NewStatementImport;
import dev.abgleich.application.statement.port.out.ParsedStatementFile;
import dev.abgleich.application.statement.port.out.StatementImportRepositoryPort;
import dev.abgleich.application.statement.port.out.StatementParserPort;
import dev.abgleich.application.statement.port.out.StatementSniff;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import dev.abgleich.domain.statement.Statement;
import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Reads a statement file once, as a stream: the first bytes choose the parser, the whole content
 * feeds the SHA-256 fingerprint, and a size limit stops oversized files early (B14, B42).
 *
 * <p>Duplicates are not checked with a query before storing: two identical uploads at the same
 * moment would both pass such a check. The database constraint decides, and the loser gets the
 * earlier import back (B21).
 */
public final class ImportStatementService implements ImportStatementUseCase {

    private final List<StatementParserPort> parsers;
    private final StatementImportRepositoryPort repository;
    private final Clock clock;

    public ImportStatementService(List<StatementParserPort> parsers, StatementImportRepositoryPort repository,
            Clock clock) {
        this.parsers = List.copyOf(parsers);
        this.repository = Objects.requireNonNull(repository, "repository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ImportResult importStatement(ImportStatementCommand command) {
        Objects.requireNonNull(command, "command");
        Instant receivedAt = clock.instant();
        ReadFile file = read(command);
        if (file.parsed().isNotification()) {
            return new ImportResult(Outcome.ENRICHED, file.parsed().format(), List.of(),
                    repository.enrich(file.parsed().notifications()));
        }

        List<NewStatementImport> imports = file.parsed().statements().stream()
                .map(statement -> new NewStatementImport(UUID.randomUUID(), command.source(), file.parsed().format(),
                        file.sha256(), file.parsed().messageId(), statement, receivedAt))
                .toList();
        try {
            return new ImportResult(Outcome.IMPORTED, file.parsed().format(), repository.store(imports));
        } catch (DuplicateImportException duplicate) {
            List<Iban> accounts = file.parsed().statements().stream().map(Statement::account).toList();
            List<ImportedStatement> previous =
                    repository.findPrevious(file.sha256(), file.parsed().messageId(), accounts);
            return new ImportResult(Outcome.ALREADY_IMPORTED, file.parsed().format(), previous);
        }
    }

    private ReadFile read(ImportStatementCommand command) {
        MessageDigest sha256 = sha256();
        try (InputStream raw = command.content().open();
                InputStream limited = new SizeLimitedInputStream(raw, MAX_FILE_BYTES);
                BufferedInputStream in = new BufferedInputStream(new DigestInputStream(limited, sha256))) {
            StatementParserPort parser = parserFor(in);
            ParsedStatementFile parsed = parser.parse(in);
            // A parser may stop before the last byte; the fingerprint must cover the whole file.
            in.transferTo(OutputStream.nullOutputStream());
            return new ReadFile(parsed, HexFormat.of().formatHex(sha256.digest()));
        } catch (IOException e) {
            throw new InvalidStatementException(Reason.MALFORMED_FILE, "The uploaded file could not be read", e);
        }
    }

    private StatementParserPort parserFor(BufferedInputStream in) throws IOException {
        in.mark(StatementSniff.MAX_BYTES);
        StatementSniff sniff = StatementSniff.of(in.readNBytes(StatementSniff.MAX_BYTES));
        in.reset();
        return parsers.stream()
                .filter(parser -> parser.canParse(sniff))
                .findFirst()
                .orElseThrow(() -> new InvalidStatementException(Reason.UNKNOWN_FORMAT,
                        "The file is not a supported bank statement (camt.053 or Norma 43)"));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Every Java runtime must provide SHA-256", e);
        }
    }

    private record ReadFile(ParsedStatementFile parsed, String sha256) {
    }

    private static final class SizeLimitedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        SizeLimitedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                count(read);
            }
            return read;
        }

        private void count(int bytes) {
            count += bytes;
            if (count > limit) {
                throw new InvalidStatementException(Reason.FORBIDDEN_CONTENT,
                        "The file is larger than " + limit / (1024 * 1024) + " MB");
            }
        }
    }
}
