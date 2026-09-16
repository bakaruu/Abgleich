package dev.abgleich.adapter.in.sftp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.adapter.in.sftp.SftpStatementWatcher.PollResult;
import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.in.StatementProcessed;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.application.port.out.StorageException;
import dev.abgleich.domain.statement.InvalidStatementException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.integration.sftp.session.DefaultSftpSessionFactory;

class SftpStatementWatcherTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T05:30:00Z"), ZoneOffset.UTC);
    private static final String COMPLETE = "<Document>complete statement</Document>";

    private static EmbeddedSftpServer sftp;

    /** What the import received; a file starting with "broken" is refused like a failed balance check. */
    private final List<String> imported = new ArrayList<>();
    private boolean storageDown;
    private final ProcessStatementUseCase process = command -> {
        assertThat(command.source()).isEqualTo(ImportSource.SFTP);
        if (storageDown) {
            throw new StorageException("database down", null);
        }
        String content;
        try (InputStream in = command.content().open()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (content.startsWith("broken")) {
            throw new InvalidStatementException(InvalidStatementException.Reason.UNBALANCED,
                    "Opening balance plus movements does not equal the closing balance");
        }
        imported.add(content);
        return new StatementProcessed(new ImportResult(ImportResult.Outcome.IMPORTED, StatementFormat.CAMT053_V04,
                List.of()), List.of());
    };

    private SftpStatementWatcher watcher;

    @BeforeAll
    static void startServer() {
        sftp = EmbeddedSftpServer.start();
    }

    @AfterAll
    static void stopServer() {
        sftp.close();
    }

    @BeforeEach
    void setUp() {
        sftp.clear();
        DefaultSftpSessionFactory sessions = sftp.sessionFactory();
        watcher = new SftpStatementWatcher(sessions, SftpFolders.under("/"), process, CLOCK, 20);
    }

    @Test
    void B25_partial_file_is_not_picked() throws IOException {
        write("camt053-2026-09-15.xml", "<Document>half writ");

        PollResult result = watcher.pollInbox();

        assertThat(result).isEqualTo(new PollResult(0, 0, 0));
        assertThat(imported).isEmpty();
        assertThat(sftp.inbox().resolve("camt053-2026-09-15.xml")).as("left for the bank to finish").exists();
    }

    @Test
    void B25_file_with_its_done_marker_is_imported_and_moved_to_processed() throws IOException {
        write("camt053-2026-09-15.xml", COMPLETE);
        write("camt053-2026-09-15.xml.done", "");

        PollResult result = watcher.pollInbox();

        assertThat(result).isEqualTo(new PollResult(1, 0, 0));
        assertThat(imported).containsExactly(COMPLETE);
        assertThat(files("inbox")).isEmpty();
        assertThat(files("processed")).containsExactly("20260916T053000000Z-camt053-2026-09-15.xml");
        assertThat(watcher.pollInbox()).as("nothing is imported twice").isEqualTo(new PollResult(0, 0, 0));
    }

    @Test
    void B25_temporary_upload_names_are_never_picked_even_with_a_marker() throws IOException {
        write("camt053.xml.part", COMPLETE);
        write("camt053.xml.part.done", "");
        write(".hidden.xml", COMPLETE);
        write(".hidden.xml.done", "");
        write("marker-without-file.xml.done", "");

        assertThat(watcher.pollInbox()).isEqualTo(new PollResult(0, 0, 0));
        assertThat(imported).isEmpty();
    }

    @Test
    void B11_truncated_file_that_got_a_marker_is_rejected_and_moved_to_error_with_the_reason() throws IOException {
        write("norma43.txt", "broken: no end record");
        write("norma43.txt.done", "");

        PollResult result = watcher.pollInbox();

        assertThat(result).isEqualTo(new PollResult(0, 1, 0));
        assertThat(files("inbox")).isEmpty();
        assertThat(files("error")).containsExactly("20260916T053000000Z-norma43.txt",
                "20260916T053000000Z-norma43.txt.error.txt");
        assertThat(Files.readString(sftp.root().resolve("error/20260916T053000000Z-norma43.txt.error.txt")))
                .isEqualTo("UNBALANCED: Opening balance plus movements does not equal the closing balance\n");
    }

    @Test
    void files_stay_in_the_inbox_while_storage_is_unavailable() throws IOException {
        write("a.xml", COMPLETE);
        write("a.xml.done", "");
        write("b.xml", COMPLETE);
        write("b.xml.done", "");
        storageDown = true;

        assertThat(watcher.pollInbox()).isEqualTo(new PollResult(0, 0, 2));
        assertThat(files("inbox")).containsExactly("a.xml", "a.xml.done", "b.xml", "b.xml.done");

        storageDown = false;
        assertThat(watcher.pollInbox()).isEqualTo(new PollResult(2, 0, 0));
    }

    @Test
    void only_plain_names_are_accepted() {
        assertThat(SftpStatementWatcher.acceptableName("camt053_2026-09-15.xml")).isTrue();
        assertThat(SftpStatementWatcher.acceptableName("..")).isFalse();
        assertThat(SftpStatementWatcher.acceptableName("a..xml")).isFalse();
        assertThat(SftpStatementWatcher.acceptableName("name with spaces.xml")).isFalse();
        assertThat(SftpStatementWatcher.acceptableName("x.error.txt")).isFalse();
        assertThat(SftpStatementWatcher.acceptableName("X.TMP")).isFalse();
    }

    @Test
    void unreachable_server_is_reported_without_touching_anything() {
        DefaultSftpSessionFactory nowhere = new DefaultSftpSessionFactory(false);
        nowhere.setHost("127.0.0.1");
        nowhere.setPort(1);
        nowhere.setUser("bank");
        nowhere.setPassword("wrong");
        nowhere.setTimeout(2_000);
        SftpStatementWatcher unreachable = new SftpStatementWatcher(nowhere, SftpFolders.under("/"), process, CLOCK, 20);

        assertThatThrownBy(unreachable::pollInbox).isInstanceOf(SftpUnavailableException.class);
    }

    private static void write(String name, String content) throws IOException {
        Files.writeString(sftp.inbox().resolve(name), content, StandardCharsets.UTF_8);
    }

    private static List<String> files(String folder) throws IOException {
        Path path = sftp.root().resolve(folder);
        if (!Files.isDirectory(path)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(path)) {
            return files.map(file -> file.getFileName().toString()).sorted().toList();
        }
    }
}
