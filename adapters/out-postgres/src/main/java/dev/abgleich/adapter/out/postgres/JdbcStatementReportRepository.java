package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.StorageException;
import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.application.statement.StatementReport;
import dev.abgleich.application.statement.port.out.StatementReportRepositoryPort;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.TransactionStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcStatementReportRepository implements StatementReportRepositoryPort {

    private final JdbcClient client;

    public JdbcStatementReportRepository(DataSource dataSource) {
        this.client = JdbcClient.create(dataSource);
    }

    @Override
    public Optional<StatementReport> findReport(UUID importId) {
        try {
            return client.sql("""
                            select id, account_iban, format, source, currency, opening_bal, opening_date,
                                   closing_bal, closing_date, received_at
                              from statement_import
                             where id = ?
                            """)
                    .param(importId)
                    .query((rs, row) -> header(rs))
                    .optional()
                    .map(header -> header.withLines(lines(importId)));
        } catch (DataAccessException e) {
            throw new StorageException("The statement report could not be read", e);
        }
    }

    private List<StatementReport.Line> lines(UUID importId) {
        return client.sql("""
                        select t.id, t.booking_date, t.direction, t.amount, t.currency, t.counterparty_name,
                               t.remittance_text, t.reference, t.status,
                               i.invoice_number, a.rule, a.status as allocation_status, a.explanation
                          from bank_transaction t
                          left join allocation a
                                 on a.transaction_id = t.id and a.status in ('PROPOSED', 'CONFIRMED')
                          left join invoice i on i.id = a.invoice_id
                         where t.import_id = ?
                         order by t.booking_date, t.dedup_key, i.invoice_number
                        """)
                .param(importId)
                .query((rs, row) -> line(rs))
                .list();
    }

    private static Header header(ResultSet rs) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new Header(
                rs.getObject("id", UUID.class),
                Iban.of(rs.getString("account_iban")),
                StatementFormat.valueOf(rs.getString("format")),
                ImportSource.valueOf(rs.getString("source")),
                new Balance(new Money(rs.getBigDecimal("opening_bal"), currency), rs.getObject("opening_date", LocalDate.class)),
                new Balance(new Money(rs.getBigDecimal("closing_bal"), currency), rs.getObject("closing_date", LocalDate.class)),
                rs.getObject("received_at", OffsetDateTime.class));
    }

    private static StatementReport.Line line(ResultSet rs) throws SQLException {
        String rule = rs.getString("rule");
        String allocationStatus = rs.getString("allocation_status");
        return new StatementReport.Line(
                rs.getObject("id", UUID.class),
                rs.getObject("booking_date", LocalDate.class),
                Direction.valueOf(rs.getString("direction")),
                new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
                rs.getString("counterparty_name"),
                rs.getString("remittance_text"),
                rs.getString("reference"),
                TransactionStatus.valueOf(rs.getString("status")),
                rs.getString("invoice_number"),
                rule == null ? null : MatchRule.valueOf(rule),
                allocationStatus == null ? null : AllocationStatus.valueOf(allocationStatus),
                rs.getString("explanation"));
    }

    private record Header(UUID id, Iban account, StatementFormat format, ImportSource source, Balance opening,
            Balance closing, OffsetDateTime receivedAt) {

        StatementReport withLines(List<StatementReport.Line> lines) {
            return new StatementReport(id, account, format, source, opening, closing, receivedAt.toInstant(), lines);
        }
    }
}
