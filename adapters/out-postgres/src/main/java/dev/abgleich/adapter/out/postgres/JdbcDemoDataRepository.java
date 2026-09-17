package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.port.out.DemoDataPort;
import dev.abgleich.application.port.out.StorageException;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * B43: the demo is emptied by truncating every table of the schema except the migration history and the job
 * locks. The tables are read from the catalogue, not listed here, so a table added by a later migration is
 * emptied too instead of silently keeping visitors' data.
 */
public final class JdbcDemoDataRepository implements DemoDataPort {

    static final Set<String> KEPT_TABLES = Set.of("flyway_schema_history", "shedlock");
    private static final Pattern PLAIN_NAME = Pattern.compile("[a-z_][a-z0-9_]*");

    private final JdbcClient client;

    public JdbcDemoDataRepository(DataSource dataSource) {
        this.client = JdbcClient.create(dataSource);
    }

    @Override
    public void deleteAllData() {
        try {
            List<String> tables = client.sql("""
                            select table_name from information_schema.tables
                             where table_schema = current_schema() and table_type = 'BASE TABLE'
                             order by table_name
                            """)
                    .query(String.class)
                    .list()
                    .stream()
                    .filter(table -> !KEPT_TABLES.contains(table))
                    .toList();
            if (tables.isEmpty()) {
                return;
            }
            tables.forEach(table -> {
                if (!PLAIN_NAME.matcher(table).matches()) {
                    throw new IllegalStateException("Unexpected table name " + table);
                }
            });
            client.sql("truncate " + tables.stream().map(table -> '"' + table + '"').collect(Collectors.joining(", ")))
                    .update();
        } catch (DataAccessException e) {
            throw new StorageException("The demo data could not be deleted", e);
        }
    }

    @Override
    public long storedBytes() {
        try {
            return client.sql("select pg_database_size(current_database())").query(Long.class).single();
        } catch (DataAccessException e) {
            throw new StorageException("The database size could not be read", e);
        }
    }
}
