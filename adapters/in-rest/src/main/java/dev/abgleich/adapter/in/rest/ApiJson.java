package dev.abgleich.adapter.in.rest;

import dev.abgleich.application.invoice.InvoiceDetail;
import dev.abgleich.application.invoice.InvoiceView;
import dev.abgleich.application.invoice.port.in.InvoiceQuery;
import dev.abgleich.application.reconciliation.ReviewItem;
import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import dev.abgleich.application.reconciliation.port.in.ReviewQueueQuery;
import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.application.statement.StatementReport;
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

    record UploadResponse(String outcome, String format, List<ImportedStatementJson> statements,
            List<EnrichedJson> notifications) {
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

    record ReviewItemJson(UUID transactionId, String version, String account, LocalDate bookingDate, MoneyJson amount,
            String counterpartyName, String remittanceText, String reference, List<ProposalJson> proposals) {

        static ReviewItemJson of(ReviewItem item) {
            return new ReviewItemJson(item.transactionId(), ETags.of(item.version()), item.account().value(),
                    item.bookingDate(), MoneyJson.of(item.amount()), item.counterpartyName(), item.remittanceText(),
                    item.reference(), item.proposals().stream().map(ProposalJson::of).toList());
        }
    }

    record ProposalJson(UUID proposalId, String rule, String confidence, String explanation, List<ShareJson> invoices) {
        static ProposalJson of(ReviewItem.Proposal proposal) {
            return new ProposalJson(proposal.groupId(), proposal.rule().name(),
                    proposal.confidence().value().toPlainString(), proposal.explanation(),
                    proposal.shares().stream().map(ShareJson::of).toList());
        }
    }

    record ShareJson(UUID invoiceId, String invoiceNumber, String debtorName, MoneyJson outstanding, MoneyJson allocated,
            MoneyJson chargesWrittenOff, String invoiceStatus, LocalDate dueDate) {
        static ShareJson of(ReviewItem.Share share) {
            return new ShareJson(share.invoiceId(), share.invoiceNumber(), share.debtorName(),
                    MoneyJson.of(share.outstanding()), MoneyJson.of(share.allocated()),
                    MoneyJson.of(share.chargesWrittenOff()), share.invoiceStatus().name(), share.dueDate());
        }
    }

    record RejectRequest(String reason) {
    }

    record DecisionJson(String outcome, String message) {
    }

    record InvoiceJson(UUID id, String invoiceNumber, String creditorIban, String debtorName, MoneyJson amount,
            MoneyJson paidAmount, MoneyJson outstanding, String status, LocalDate dueDate, String reference,
            String version) {
        static InvoiceJson of(InvoiceView invoice) {
            return new InvoiceJson(invoice.id(), invoice.number(), invoice.creditorAccount().value(),
                    invoice.debtorName(), MoneyJson.of(invoice.amount()), MoneyJson.of(invoice.paidAmount()),
                    MoneyJson.of(invoice.outstanding()), invoice.status().name(), invoice.dueDate(),
                    invoice.reference(), ETags.of(invoice.version()));
        }
    }

    record AllocationJson(UUID allocationId, UUID transactionId, LocalDate bookingDate, MoneyJson amount,
            MoneyJson chargesWrittenOff, String rule, String status, String explanation, String decidedBy,
            Instant decidedAt, String decisionNote) {
        static AllocationJson of(InvoiceDetail.AllocationView allocation) {
            return new AllocationJson(allocation.allocationId(), allocation.transactionId(), allocation.bookingDate(),
                    MoneyJson.of(allocation.amount()), MoneyJson.of(allocation.chargesWrittenOff()),
                    allocation.rule().name(), allocation.status().name(), allocation.explanation(),
                    allocation.decidedBy(), allocation.decidedAt(), allocation.decisionNote());
        }
    }

    record InvoiceDetailJson(InvoiceJson invoice, List<AllocationJson> allocations) {
        static InvoiceDetailJson of(InvoiceDetail detail) {
            return new InvoiceDetailJson(InvoiceJson.of(detail.invoice()),
                    detail.allocations().stream().map(AllocationJson::of).toList());
        }
    }

    record EnrichedJson(String account, int enriched, int alreadyComplete, int unknown, ReconciliationRun reconciliation) {
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
