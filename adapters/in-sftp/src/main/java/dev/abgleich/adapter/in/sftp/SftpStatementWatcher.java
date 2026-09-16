package dev.abgleich.adapter.in.sftp;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ImportStatementCommand;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.out.StorageException;
import dev.abgleich.application.service.ImportStatementService;
import dev.abgleich.domain.statement.InvalidStatementException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.sshd.sftp.client.SftpClient.DirEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.integration.file.remote.session.Session;
import org.springframework.integration.file.remote.session.SessionFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Polls the SFTP inbox and processes each finished file like an upload.
 *
 * <p>B25: an upload in progress is never read. A file is finished only when the bank has also uploaded an
 * empty marker named like the file plus {@code .done}; temporary names such as {@code .part} are never taken
 * even with a marker. If a truncated file got a marker anyway, balance validation rejects it (B11) and it is
 * moved to {@code error/} with the reason next to it.
 *
 * <p>A file is moved only after it was processed. If the application stops in between, the next poll
 * processes it again, which the import recognises as already imported (B21), and then moves it.
 *
 * <p>File names come from outside: only plain names are accepted, so no name can point outside the folders.
 * Logs contain counts and reasons, not names, which banks sometimes build from the IBAN (B41).
 *
 * <p>Not final: ShedLock wraps the scheduled method in a proxy.
 */
public class SftpStatementWatcher {

    static final String DONE_SUFFIX = ".done";
    static final String ERROR_SUFFIX = ".error.txt";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final List<String> TEMPORARY_SUFFIXES =
            List.of(".part", ".partial", ".tmp", ".temp", ".filepart", ".uploading", DONE_SUFFIX, ERROR_SUFFIX);
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'").withZone(ZoneOffset.UTC);
    private static final Logger log = LoggerFactory.getLogger(SftpStatementWatcher.class);

    private final SessionFactory<DirEntry> sessions;
    private final SftpFolders folders;
    private final ProcessStatementUseCase processStatement;
    private final Clock clock;
    private final int maxFilesPerPoll;

    public SftpStatementWatcher(SessionFactory<DirEntry> sessions, SftpFolders folders,
            ProcessStatementUseCase processStatement, Clock clock, int maxFilesPerPoll) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.folders = Objects.requireNonNull(folders, "folders");
        this.processStatement = Objects.requireNonNull(processStatement, "processStatement");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (maxFilesPerPoll < 1) {
            throw new IllegalArgumentException("maxFilesPerPoll must be at least 1");
        }
        this.maxFilesPerPoll = maxFilesPerPoll;
    }

    /** One instance polls at a time (B26); the others skip the run. */
    @Scheduled(fixedDelayString = "${abgleich.sftp.poll-interval}", initialDelayString = "${abgleich.sftp.poll-interval}")
    @SchedulerLock(name = "sftp-statement-inbox", lockAtMostFor = "${abgleich.sftp.lock-at-most-for:PT15M}")
    public void scheduledPoll() {
        try {
            PollResult result = pollInbox();
            if (result.processed() + result.rejected() + result.deferred() > 0) {
                log.info("SFTP inbox: {} processed, {} rejected, {} left for the next poll",
                        result.processed(), result.rejected(), result.deferred());
            }
        } catch (SftpUnavailableException e) {
            log.warn("SFTP inbox not reachable, trying again at the next poll: {}", e.getMessage());
        }
    }

    /**
     * @throws SftpUnavailableException if the server cannot be reached or a file operation fails
     */
    public PollResult pollInbox() {
        Session<DirEntry> session = openSession();
        try {
            ensureFolder(session, folders.processed());
            ensureFolder(session, folders.error());
            Map<String, Long> sizes = regularFiles(session);
            List<String> finished = sizes.keySet().stream()
                    .filter(name -> name.endsWith(DONE_SUFFIX))
                    .map(name -> name.substring(0, name.length() - DONE_SUFFIX.length()))
                    .filter(SftpStatementWatcher::acceptableName)
                    .filter(sizes::containsKey)
                    .sorted()
                    .limit(maxFilesPerPoll)
                    .toList();
            int processed = 0;
            int rejected = 0;
            for (String name : finished) {
                FileOutcome outcome = handle(session, name, sizes.get(name));
                if (outcome == FileOutcome.DEFERRED) {
                    // Storage is down: every other file would fail the same way. Try again at the next poll.
                    return new PollResult(processed, rejected, finished.size() - processed - rejected);
                }
                if (outcome == FileOutcome.PROCESSED) {
                    processed++;
                } else {
                    rejected++;
                }
            }
            return new PollResult(processed, rejected, 0);
        } catch (IOException e) {
            throw new SftpUnavailableException("An SFTP operation failed", e);
        } finally {
            session.close();
        }
    }

    private FileOutcome handle(Session<DirEntry> session, String name, long size) throws IOException {
        String stamped = STAMP.format(clock.instant()) + "-" + name;
        if (size > ImportStatementService.MAX_FILE_BYTES) {
            moveToError(session, name, stamped, "FORBIDDEN_CONTENT: The file is larger than "
                    + ImportStatementService.MAX_FILE_BYTES / (1024 * 1024) + " MB");
            return FileOutcome.REJECTED;
        }
        byte[] content = download(session, folders.inbox() + "/" + name);
        try {
            processStatement.process(new ImportStatementCommand(ImportSource.SFTP, () -> new ByteArrayInputStream(content)));
        } catch (InvalidStatementException invalid) {
            log.warn("SFTP file rejected: {}", invalid.reason());
            moveToError(session, name, stamped, invalid.reason() + ": " + invalid.getMessage());
            return FileOutcome.REJECTED;
        } catch (StorageException unavailable) {
            log.warn("SFTP file left in the inbox, storage unavailable");
            return FileOutcome.DEFERRED;
        }
        session.rename(folders.inbox() + "/" + name, folders.processed() + "/" + stamped);
        session.remove(folders.inbox() + "/" + name + DONE_SUFFIX);
        return FileOutcome.PROCESSED;
    }

    private void moveToError(Session<DirEntry> session, String name, String stamped, String reason) throws IOException {
        session.rename(folders.inbox() + "/" + name, folders.error() + "/" + stamped);
        session.write(new ByteArrayInputStream((reason + "\n").getBytes(StandardCharsets.UTF_8)),
                folders.error() + "/" + stamped + ERROR_SUFFIX);
        session.remove(folders.inbox() + "/" + name + DONE_SUFFIX);
    }

    private Map<String, Long> regularFiles(Session<DirEntry> session) throws IOException {
        Map<String, Long> sizes = new HashMap<>();
        for (DirEntry entry : session.list(folders.inbox())) {
            if (entry.getAttributes().isRegularFile()) {
                sizes.put(entry.getFilename(), entry.getAttributes().getSize());
            }
        }
        return sizes;
    }

    static boolean acceptableName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return SAFE_NAME.matcher(name).matches() && !name.contains("..")
                && TEMPORARY_SUFFIXES.stream().noneMatch(lower::endsWith);
    }

    private static byte[] download(Session<DirEntry> session, String path) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        session.read(path, new BoundedOutputStream(bytes, ImportStatementService.MAX_FILE_BYTES + 1));
        return bytes.toByteArray();
    }

    private static void ensureFolder(Session<DirEntry> session, String folder) throws IOException {
        if (!session.exists(folder)) {
            session.mkdir(folder);
        }
    }

    private Session<DirEntry> openSession() {
        try {
            return sessions.getSession();
        } catch (IllegalStateException e) {
            throw new SftpUnavailableException("Could not open an SFTP session", e);
        }
    }

    private enum FileOutcome { PROCESSED, REJECTED, DEFERRED }

    /**
     * @param processed files imported now or recognised as imported before, moved to {@code processed/}
     * @param rejected files moved to {@code error/}
     * @param deferred finished files left in the inbox because storage was unavailable
     */
    public record PollResult(int processed, int rejected, int deferred) {
    }

    /** Stops a download that grows past the limit, so the import's own size check can reject it (B42). */
    private static final class BoundedOutputStream extends OutputStream {

        private final OutputStream out;
        private final long limit;
        private long written;

        BoundedOutputStream(OutputStream out, long limit) {
            this.out = out;
            this.limit = limit;
        }

        @Override
        public void write(int b) throws IOException {
            if (written < limit) {
                out.write(b);
                written++;
            }
        }

        @Override
        public void write(byte[] buffer, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, limit - written);
            if (allowed > 0) {
                out.write(buffer, offset, allowed);
                written += allowed;
            }
        }
    }
}
