package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ReconciliationSummary;
import dev.abgleich.application.port.in.ReconciliationSummary.ChannelImports;
import dev.abgleich.application.port.in.ReconciliationSummary.Payments;
import dev.abgleich.application.port.in.ReconciliationSummary.RuleOutcome;
import dev.abgleich.application.port.out.StorageException;
import dev.abgleich.application.port.out.SummaryRepositoryPort;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Currency;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * All counts come from one repeatable-read snapshot, so the figures on the Summary screen add up even while
 * imports and decisions happen.
 *
 * <p>Payments settled without a person are the matched credits with an allocation confirmed by
 * {@value Allocation#SYSTEM}. Proposals rejected only because another proposal for the payment was confirmed
 * are not counted as rejected: nobody disagreed with their rule.
 */
public final class JdbcSummaryRepository implements SummaryRepositoryPort {

    private final JdbcClient client;
    private final TransactionTemplate snapshot;

    public JdbcSummaryRepository(DataSource dataSource, TransactionTemplate transactions) {
        this.client = JdbcClient.create(dataSource);
        this.snapshot = new TransactionTemplate(transactions.getTransactionManager());
        snapshot.setReadOnly(true);
        snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override
    public ReconciliationSummary summary() {
        try {
            return snapshot.execute(status -> {
                List<Money> unmatched = new ArrayList<>();
                List<Money> waiting = new ArrayList<>();
                client.sql("""
                                select currency, status, sum(amount) as total
                                  from bank_transaction
                                 where direction = 'CREDIT' and status in ('UNMATCHED', 'PROPOSED')
                                 group by currency, status
                                 order by currency
                                """)
                        .query(rs -> {
                            Money total = new Money(rs.getBigDecimal("total"), Currency.getInstance(rs.getString("currency")));
                            ("UNMATCHED".equals(rs.getString("status")) ? unmatched : waiting).add(total);
                        });
                return new ReconciliationSummary(payments(), unmatched, waiting, rules(), imports(),
                        client.sql("select count(*) from outbox_event where published_at is null").query(Long.class).single());
            });
        } catch (DataAccessException e) {
            throw new StorageException("The summary could not be read", e);
        }
    }

    private Payments payments() {
        return client.sql("""
                        select count(*) as credits,
                               count(*) filter (where status = 'MATCHED' and auto) as auto_confirmed,
                               count(*) filter (where status = 'MATCHED' and not auto) as confirmed_by_review,
                               count(*) filter (where status = 'PROPOSED') as waiting,
                               count(*) filter (where status = 'UNMATCHED') as unmatched,
                               count(*) filter (where status = 'REVERSED') as reversed
                          from (select t.status,
                                       exists (select 1 from allocation a
                                                where a.transaction_id = t.id and a.status = 'CONFIRMED'
                                                  and a.decided_by = ?) as auto
                                  from bank_transaction t
                                 where t.direction = 'CREDIT') credits
                        """)
                .param(Allocation.SYSTEM)
                .query((rs, row) -> new Payments(rs.getLong("credits"), rs.getLong("auto_confirmed"),
                        rs.getLong("confirmed_by_review"), rs.getLong("waiting"), rs.getLong("unmatched"),
                        rs.getLong("reversed")))
                .single();
    }

    private List<RuleOutcome> rules() {
        Map<MatchRule, RuleOutcome> byRule = new EnumMap<>(MatchRule.class);
        client.sql("""
                        select rule,
                               count(distinct group_id) filter (where status = 'CONFIRMED' and decided_by = ?) as auto_confirmed,
                               count(distinct group_id) filter (where status = 'CONFIRMED' and decided_by <> ?) as confirmed,
                               count(distinct group_id) filter (where status = 'REJECTED'
                                                                  and decision_note is distinct from ?) as rejected,
                               count(distinct group_id) filter (where status = 'PROPOSED') as pending
                          from allocation
                         group by rule
                        """)
                .params(Allocation.SYSTEM, Allocation.SYSTEM, Allocation.SUPERSEDED)
                .query(rs -> {
                    MatchRule rule = MatchRule.valueOf(rs.getString("rule"));
                    byRule.put(rule, new RuleOutcome(rule, rs.getLong("auto_confirmed"), rs.getLong("confirmed"),
                            rs.getLong("rejected"), rs.getLong("pending")));
                });
        return Arrays.stream(MatchRule.values())
                .map(rule -> byRule.getOrDefault(rule, new RuleOutcome(rule, 0, 0, 0, 0)))
                .toList();
    }

    private List<ChannelImports> imports() {
        return client.sql("select source, count(distinct file_sha256) as files from statement_import group by source")
                .query((rs, row) -> new ChannelImports(ImportSource.valueOf(rs.getString("source")), rs.getLong("files")))
                .list()
                .stream()
                .sorted(Comparator.comparing(ChannelImports::source))
                .toList();
    }
}
