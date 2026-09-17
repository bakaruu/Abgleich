package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.StorageException;
import dev.abgleich.application.events.port.out.OutboxRepositoryPort;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Reads and marks outbox rows. Rows are inserted by the repositories that store the change causing the
 * event ({@link OutboxRows}), never here, so an event cannot exist without its change (B23).
 */
public final class JdbcOutboxRepository implements OutboxRepositoryPort {

    private final JdbcClient client;

    public JdbcOutboxRepository(DataSource dataSource) {
        this.client = JdbcClient.create(dataSource);
    }

    @Override
    public List<PendingEvent> findUnpublished(int limit) {
        return read("Unpublished events could not be read", () -> client.sql("select " + OutboxRows.COLUMNS + """
                        , attempts
                         from outbox_event
                        where published_at is null
                        order by position
                        limit ?
                        """)
                .param(limit)
                .query((rs, row) -> new PendingEvent(OutboxRows.map(rs), rs.getInt("attempts")))
                .list());
    }

    @Override
    public void markPublished(UUID eventId, Instant publishedAt) {
        write("The event could not be marked as published", () -> client.sql("""
                        update outbox_event set published_at = ?, last_error = null
                         where id = ? and published_at is null
                        """)
                .params(OffsetDateTime.ofInstant(publishedAt, ZoneOffset.UTC), eventId)
                .update());
    }

    @Override
    public void markFailed(UUID eventId, String reason) {
        write("The failed attempt could not be recorded", () -> client.sql("""
                        update outbox_event set attempts = attempts + 1, last_error = left(?, 500)
                         where id = ? and published_at is null
                        """)
                .params(reason, eventId)
                .update());
    }

    @Override
    public long countUnpublished() {
        return read("Unpublished events could not be counted", () -> client.sql(
                        "select count(*) from outbox_event where published_at is null")
                .query(Long.class)
                .single());
    }

    @Override
    public int deletePublishedBefore(Instant publishedBefore) {
        return read("Published events could not be deleted", () -> client.sql("""
                        delete from outbox_event where published_at is not null and published_at < ?
                        """)
                .param(OffsetDateTime.ofInstant(publishedBefore, ZoneOffset.UTC))
                .update());
    }

    private static <T> T read(String failure, Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new StorageException(failure, e);
        }
    }

    private static void write(String failure, Supplier<Integer> update) {
        read(failure, update);
    }
}
