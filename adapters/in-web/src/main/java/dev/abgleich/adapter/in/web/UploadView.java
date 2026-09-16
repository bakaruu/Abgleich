package dev.abgleich.adapter.in.web;

import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ImportedStatement;
import dev.abgleich.application.port.in.ReconciliationRun;
import dev.abgleich.application.port.in.StatementProcessed;
import dev.abgleich.application.port.in.StatementReport;
import dev.abgleich.application.port.in.StatementReportQuery;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.money.Direction;
import java.util.List;
import java.util.stream.IntStream;

/** What the result fragment shows. Labels are decided here so the template stays free of logic. */
public record UploadView(String title, boolean alreadyImported, String format, List<AccountView> accounts) {

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
            case NORMA43 -> "Norma 43 (AEB)";
        };
        return new UploadView(title, imported.outcome() == ImportResult.Outcome.ALREADY_IMPORTED, format, accounts);
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
