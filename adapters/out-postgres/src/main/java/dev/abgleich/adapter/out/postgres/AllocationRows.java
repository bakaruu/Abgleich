package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.StaleDataException;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.Confidence;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Allocation rows: one mapping and one insert and decision statement for every repository. */
final class AllocationRows {

    /** Columns of {@code allocation a}, plus the currency of its transaction {@code t}. */
    static final String COLUMNS = """
            a.id, a.transaction_id, a.invoice_id, a.group_id, a.amount, a.charges_written_off, a.rule,
            a.confidence, a.explanation, a.status, a.decided_by, a.decided_at, a.decision_note, a.created_at,
            a.version, t.currency""";

    private AllocationRows() {
    }

    static void insert(JdbcClient client, Allocation allocation) {
        client.sql("""
                        insert into allocation
                            (id, transaction_id, invoice_id, group_id, amount, charges_written_off, rule, confidence,
                             explanation, status, decided_by, decided_at, decision_note, created_at, version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(allocation.id(), allocation.transactionId(), allocation.invoiceId(), allocation.groupId(),
                        allocation.amount().amount(), allocation.chargesWrittenOff().amount(), allocation.rule().name(),
                        allocation.confidence().value(), allocation.explanation(), allocation.status().name(),
                        allocation.decidedBy(), timestamp(allocation.decidedAt()), allocation.decisionNote(),
                        timestamp(allocation.createdAt()), allocation.version())
                .update();
    }

    /**
     * Stores a decision taken on an allocation read in {@code from} status. Zero rows means someone
     * decided it in between (B33).
     */
    static void decide(JdbcClient client, Allocation decided, AllocationStatus from) {
        int updated = client.sql("""
                        update allocation
                           set status = ?, decided_by = ?, decided_at = ?, decision_note = ?, version = version + 1
                         where id = ? and status = ? and version = ?
                        """)
                .params(decided.status().name(), decided.decidedBy(), timestamp(decided.decidedAt()),
                        decided.decisionNote(), decided.id(), from.name(), decided.version())
                .update();
        if (updated != 1) {
            throw new StaleDataException("The proposal was decided by someone else");
        }
    }

    static Allocation map(ResultSet rs) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
        return new Allocation(
                rs.getObject("id", UUID.class),
                rs.getObject("transaction_id", UUID.class),
                rs.getObject("invoice_id", UUID.class),
                rs.getObject("group_id", UUID.class),
                new Money(rs.getBigDecimal("amount"), currency),
                new Money(rs.getBigDecimal("charges_written_off"), currency),
                MatchRule.valueOf(rs.getString("rule")),
                new Confidence(rs.getBigDecimal("confidence")),
                rs.getString("explanation"),
                AllocationStatus.valueOf(rs.getString("status")),
                rs.getString("decided_by"),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getString("decision_note"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getLong("version"));
    }

    static OffsetDateTime timestamp(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
