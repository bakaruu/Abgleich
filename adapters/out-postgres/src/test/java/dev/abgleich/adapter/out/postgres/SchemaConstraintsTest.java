package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Verifies that the database itself rejects the data corruptions listed in the bug catalogue,
 * independently of any application code.
 */
@Testcontainers
class SchemaConstraintsTest {

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";
    private static final String IBAN = "CH4431999123000889012";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withCreateContainerCmdModifier(cmd -> cmd.withName("abgleich-test-postgres-schema-constraints"));

    private Connection connection;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
    }

    @BeforeEach
    void openConnection() throws SQLException {
        connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        connection.setAutoCommit(false);
    }

    @AfterEach
    void rollback() throws SQLException {
        connection.rollback();
        connection.close();
    }

    @Test
    void B21_same_file_cannot_be_imported_twice_for_the_same_account() throws SQLException {
        String sha = "a".repeat(64);
        insertImport(UUID.randomUUID(), sha, null);

        assertSqlState(() -> insertImport(UUID.randomUUID(), sha, null), UNIQUE_VIOLATION);
    }

    @Test
    void B21_same_camt_message_id_cannot_be_imported_twice() throws SQLException {
        insertImport(UUID.randomUUID(), "b".repeat(64), "MSG-2026-09-15-001");

        assertSqlState(() -> insertImport(UUID.randomUUID(), "c".repeat(64), "MSG-2026-09-15-001"), UNIQUE_VIOLATION);
    }

    @Test
    void B21_norma43_imports_without_message_id_do_not_collide() throws SQLException {
        insertImport(UUID.randomUUID(), "d".repeat(64), null);
        insertImport(UUID.randomUUID(), "e".repeat(64), null);
    }

    @Test
    void B10_direction_must_be_credit_or_debit() throws SQLException {
        UUID importId = insertImport(UUID.randomUUID(), "f".repeat(64), null);

        assertSqlState(() -> insertTransaction(importId, "key-1", "CRDT", "480.00", null), CHECK_VIOLATION);
    }

    @Test
    void B10_amounts_are_unsigned() throws SQLException {
        UUID importId = insertImport(UUID.randomUUID(), "1".repeat(64), null);

        assertSqlState(() -> insertTransaction(importId, "key-1", "DEBIT", "-12.00", null), CHECK_VIOLATION);
    }

    @Test
    void B16_same_dedup_key_cannot_be_stored_twice() throws SQLException {
        UUID importId = insertImport(UUID.randomUUID(), "2".repeat(64), null);
        insertTransaction(importId, "BNK20260915000123", "CREDIT", "1250.00", null);

        assertSqlState(() -> insertTransaction(importId, "BNK20260915000123", "CREDIT", "1250.00", null), UNIQUE_VIOLATION);
    }

    @Test
    void B04_references_keep_leading_zeros() throws SQLException {
        UUID importId = insertImport(UUID.randomUUID(), "3".repeat(64), null);
        String reference = "000000000000000000000000000";
        UUID transactionId = insertTransaction(importId, "key-zeros", "CREDIT", "10.00", reference);

        try (PreparedStatement select = connection.prepareStatement("select reference from bank_transaction where id = ?")) {
            select.setObject(1, transactionId);
            try (ResultSet rs = select.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getString(1)).isEqualTo(reference);
            }
        }
    }

    @Test
    void B24_invoice_number_is_unique() throws SQLException {
        insertInvoice("F-2026-0143");

        assertSqlState(() -> insertInvoice("F-2026-0143"), UNIQUE_VIOLATION);
    }

    @Test
    void B33_only_one_active_allocation_per_transaction_and_invoice() throws SQLException {
        UUID importId = insertImport(UUID.randomUUID(), "4".repeat(64), null);
        UUID transactionId = insertTransaction(importId, "key-alloc", "CREDIT", "480.00", null);
        UUID invoiceId = insertInvoice("F-2026-0142");

        insertAllocation(transactionId, invoiceId, "REJECTED");
        insertAllocation(transactionId, invoiceId, "PROPOSED");

        assertSqlState(() -> insertAllocation(transactionId, invoiceId, "CONFIRMED"), UNIQUE_VIOLATION);
    }

    private UUID insertImport(UUID id, String sha, String messageId) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into statement_import
                    (id, source, format, file_sha256, message_id, account_iban, currency,
                     opening_bal, opening_date, closing_bal, closing_date, status, received_at)
                values (?, 'REST', 'CAMT053_V04', ?, ?, ?, 'CHF', 0, date '2026-09-14', 0, date '2026-09-15', 'STORED', now())
                """)) {
            insert.setObject(1, id);
            insert.setString(2, sha);
            insert.setString(3, messageId);
            insert.setString(4, IBAN);
            insert.executeUpdate();
        }
        return id;
    }

    private UUID insertTransaction(UUID importId, String dedupKey, String direction, String amount, String reference)
            throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into bank_transaction
                    (id, import_id, account_iban, dedup_key, booking_date, value_date,
                     direction, amount, currency, reference, status)
                values (?, ?, ?, ?, date '2026-09-15', date '2026-09-15', ?, ?::numeric, 'CHF', ?, 'UNMATCHED')
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, importId);
            insert.setString(3, IBAN);
            insert.setString(4, dedupKey);
            insert.setString(5, direction);
            insert.setString(6, amount);
            insert.setString(7, reference);
            insert.executeUpdate();
        }
        return id;
    }

    private UUID insertInvoice(String number) throws SQLException {
        UUID id = UUID.randomUUID();
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into invoice
                    (id, invoice_number, creditor_iban, debtor_name, amount, currency, due_date, status)
                values (?, ?, ?, 'Keller GmbH', 480.00, 'CHF', date '2026-09-30', 'OPEN')
                """)) {
            insert.setObject(1, id);
            insert.setString(2, number);
            insert.setString(3, IBAN);
            insert.executeUpdate();
        }
        return id;
    }

    private void insertAllocation(UUID transactionId, UUID invoiceId, String status) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement("""
                insert into allocation
                    (id, transaction_id, invoice_id, amount, rule, confidence, explanation, status, created_at)
                values (?, ?, ?, 480.00, 'R4', 0.80, 'Invoice number found in remittance text', ?, now())
                """)) {
            insert.setObject(1, UUID.randomUUID());
            insert.setObject(2, transactionId);
            insert.setObject(3, invoiceId);
            insert.setString(4, status);
            insert.executeUpdate();
        }
    }

    /** Runs the statement in a savepoint so the surrounding test transaction stays usable. */
    private void assertSqlState(SqlAction action, String expectedState) throws SQLException {
        var savepoint = connection.setSavepoint();
        assertThatThrownBy(action::run)
                .isInstanceOf(SQLException.class)
                .extracting(e -> ((SQLException) e).getSQLState())
                .isEqualTo(expectedState);
        connection.rollback(savepoint);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
