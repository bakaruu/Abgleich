package dev.abgleich.adapter.in.web;

import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import dev.abgleich.application.statement.EnrichedNotification;
import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.application.statement.StatementReport;
import dev.abgleich.application.statement.port.in.ImportResult;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import dev.abgleich.application.statement.port.in.StatementReportQuery;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.money.Direction;
import java.util.List;
import java.util.stream.IntStream;

/** What the result fragment shows. Labels are decided here so the template stays free of logic. */
public record UploadView(
        String title,
        ImportResult.Outcome outcome,
        String format,
        List<AccountView> accounts,
        List<EnrichedNotification> enriched) {

    /** @param title the file name when it is known, or {@code null} */
    static UploadView of(StatementProcessed processed, StatementReportQuery reports, String title) {
        ImportResult imported = processed.imported();
        List<AccountView> accounts = IntStream.range(0, imported.statements().size())
                .mapToObj(i -> {
                    ImportedStatement statement = imported.statements().get(i);
                    StatementReport report = reports.report(statement.importId()).orElseThrow();
                    return new AccountView(statement, processed.reconciliations().get(i),
                            report.transactions().stream().map(LineView::new).toList());
                })
                .toList();
        String format = switch (imported.format()) {
            case CAMT053_V04 -> "camt.053 (ISO 20022, version 001.04)";
            case CAMT053_V08 -> "camt.053 (ISO 20022, version 001.08)";
            case CAMT054_V04 -> "camt.054 notification (ISO 20022, version 001.04)";
            case CAMT054_V08 -> "camt.054 notification (ISO 20022, version 001.08)";
            case NORMA43 -> "Norma 43 (AEB)";
            case CSV -> "Abgleich CSV";
        };
        return new UploadView(title, imported.outcome(), format, accounts, imported.enriched());
    }

    public String outcomeLabel() {
        return switch (outcome) {
            case IMPORTED -> "Imported.";
            case ALREADY_IMPORTED -> "Already imported.";
            case ENRICHED -> "Notification read.";
        };
    }

    public String outcomeText() {
        return switch (outcome) {
            case IMPORTED -> "Balances were validated and every payment was stored once.";
            case ALREADY_IMPORTED -> "This file was imported before; nothing was stored twice.";
            case ENRICHED -> "Its details were added to payments already stored; no payment was created.";
        };
    }

    public String outcomeClass() {
        return outcome == ImportResult.Outcome.ALREADY_IMPORTED ? "alert-known" : "alert-ok";
    }

    public record AccountView(ImportedStatement statement, ReconciliationRun run, List<LineView> lines) {
    }

    /** One row of the transaction table. Bank texts are passed through untouched and escaped by th:text (B32). */
    public record LineView(StatementReport.Line line) {

        public String sign() {
            return line.direction() == Direction.CREDIT ? "+" : "−";
        }

        public String statusLabel() {
            if (line.direction() == Direction.DEBIT) {
                return "Debit";
            }
            return switch (line.status()) {
                case MATCHED -> "Matched " + line.rule();
                case PROPOSED -> "Needs review";
                case UNMATCHED -> "Unmatched";
                case PARTIALLY_ALLOCATED -> "Partially allocated";
                case IGNORED -> "Ignored";
                case REVERSED -> "Reversed";
            };
        }

        public String statusClass() {
            if (line.direction() == Direction.DEBIT) {
                return "status-debit";
            }
            return switch (line.status()) {
                case MATCHED -> "status-matched";
                case PROPOSED -> "status-review";
                default -> "status-open";
            };
        }

        public boolean proposal() {
            return line.allocationStatus() == AllocationStatus.PROPOSED;
        }
    }
}
