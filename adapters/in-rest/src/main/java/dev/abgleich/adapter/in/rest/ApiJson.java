package dev.abgleich.adapter.in.rest;

import dev.abgleich.application.port.in.ImportedStatement;
import dev.abgleich.application.port.in.ReconciliationRun;
import dev.abgleich.application.port.in.StatementReport;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.Balance;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * JSON shapes of the API. Amounts travel as strings ("1250.00") so no client parses them into a
 * binary floating point number (B01).
 */
final class ApiJson {

    private ApiJson() {
    }

    record MoneyJson(String amount, String currency) {
        static MoneyJson of(Money money) {
            return new MoneyJson(money.amount().toPlainString(), money.currency().getCurrencyCode());
        }
    }

    record BalanceJson(String amount, String currency, LocalDate date) {
        static BalanceJson of(Balance balance) {
            return new BalanceJson(balance.amount().amount().toPlainString(),
                    balance.amount().currency().getCurrencyCode(), balance.date());
        }
    }

    record UploadResponse(String outcome, String format, List<ImportedStatementJson> statements) {
    }

    record ImportedStatementJson(
            UUID importId,
            String account,
            BalanceJson openingBalance,
            BalanceJson closingBalance,
            int newTransactions,
            int knownTransactions,
            ReconciliationRun reconciliation) {

        static ImportedStatementJson of(ImportedStatement statement, ReconciliationRun run) {
            return new ImportedStatementJson(statement.importId(), statement.account().value(),
                    BalanceJson.of(statement.openingBalance()), BalanceJson.of(statement.closingBalance()),
                    statement.newTransactions(), statement.knownTransactions(), run);
        }
    }

    record ReportJson(
            UUID importId,
            String account,
            String format,
            String source,
            Instant receivedAt,
            BalanceJson openingBalance,
            BalanceJson closingBalance,
            List<LineJson> transactions) {

        static ReportJson of(StatementReport report) {
            return new ReportJson(report.importId(), report.account().value(), report.format().name(),
                    report.source().name(), report.receivedAt(), BalanceJson.of(report.openingBalance()),
                    BalanceJson.of(report.closingBalance()),
                    report.transactions().stream().map(LineJson::of).toList());
        }
    }

    record LineJson(
            UUID transactionId,
            LocalDate bookingDate,
            String direction,
            MoneyJson amount,
            String counterpartyName,
            String remittanceText,
            String reference,
            String status,
            String invoiceNumber,
            String rule,
            String allocationStatus,
            String explanation) {

        static LineJson of(StatementReport.Line line) {
            return new LineJson(line.transactionId(), line.bookingDate(), line.direction().name(),
                    MoneyJson.of(line.amount()), line.counterpartyName(), line.remittanceText(), line.reference(),
                    line.status().name(), line.invoiceNumber(),
                    line.rule() == null ? null : line.rule().name(),
                    line.allocationStatus() == null ? null : line.allocationStatus().name(),
                    line.explanation());
        }
    }

    record RegisterInvoiceRequest(
            String invoiceNumber,
            String creditorIban,
            String debtorName,
            String amount,
            String currency,
            String reference,
            LocalDate dueDate) {
    }

    record InvoiceCreated(UUID id) {
    }
}
