package dev.abgleich.adapter.in.rest;

import dev.abgleich.adapter.in.rest.ApiJson.MoneyJson;
import dev.abgleich.application.reporting.ReconciliationSummary;
import dev.abgleich.application.reporting.port.in.SummaryQuery;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReportApiController {

    private final SummaryQuery summaries;

    ReportApiController(SummaryQuery summaries) {
        this.summaries = summaries;
    }

    /** The figures of the Summary screen. Percentages are decimal strings, like amounts (B01). */
    @GetMapping("/api/v1/reports/summary")
    SummaryJson summary() {
        return SummaryJson.of(summaries.summary());
    }

    record SummaryJson(PaymentsJson payments, List<MoneyJson> unmatchedAmounts, List<MoneyJson> waitingForReviewAmounts,
            List<RuleJson> rules, List<ChannelJson> imports, long outboxPending) {

        static SummaryJson of(ReconciliationSummary summary) {
            ReconciliationSummary.Payments payments = summary.payments();
            return new SummaryJson(
                    new PaymentsJson(payments.credits(), payments.autoConfirmed(), payments.confirmedByReview(),
                            payments.waitingForReview(), payments.unmatched(), payments.reversed(),
                            payments.autoReconciledPercent().toPlainString()),
                    summary.unmatchedAmounts().stream().map(MoneyJson::of).toList(),
                    summary.waitingForReviewAmounts().stream().map(MoneyJson::of).toList(),
                    summary.rules().stream()
                            .map(rule -> new RuleJson(rule.rule().name(), rule.rule().confidence().value().toPlainString(),
                                    rule.autoConfirmed(), rule.confirmed(), rule.rejected(), rule.pending(),
                                    rule.reviewerAgreementPercent().map(BigDecimal::toPlainString).orElse(null)))
                            .toList(),
                    summary.imports().stream()
                            .map(channel -> new ChannelJson(channel.source().name(), channel.files()))
                            .toList(),
                    summary.outboxPending());
        }
    }

    record PaymentsJson(long credits, long autoConfirmed, long confirmedByReview, long waitingForReview, long unmatched,
            long reversed, String autoReconciledPercent) {
    }

    /** @param reviewerAgreementPercent null while nobody decided a proposal of the rule */
    record RuleJson(String rule, String confidence, long autoConfirmed, long confirmed, long rejected, long pending,
            String reviewerAgreementPercent) {
    }

    record ChannelJson(String source, long files) {
    }
}
