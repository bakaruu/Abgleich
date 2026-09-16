package dev.abgleich.application.service;

import dev.abgleich.application.port.in.DecisionResult;
import dev.abgleich.application.port.in.DecisionResult.Outcome;
import dev.abgleich.application.port.in.ReviewProposalUseCase;
import dev.abgleich.application.port.out.ReviewRepositoryPort;
import dev.abgleich.application.port.out.ReviewRepositoryPort.ProposalGroup;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.AllocationStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Human decisions on proposals.
 *
 * <p>B33: a second click on "Confirm" finds the group already confirmed and succeeds without doing
 * anything; if both clicks race, the database refuses the second write and the re-read shows the
 * group confirmed. B34: a decision based on an old version of the payment is refused with a message.
 */
public final class ReviewService implements ReviewProposalUseCase {

    static final String SUPERSEDED = "Another proposal for this payment was confirmed";

    private final ReviewRepositoryPort reviews;
    private final Clock clock;

    public ReviewService(ReviewRepositoryPort reviews, Clock clock) {
        this.reviews = Objects.requireNonNull(reviews, "reviews");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public DecisionResult confirm(UUID groupId, long expectedPaymentVersion, String reviewer) {
        return decide(groupId, expectedPaymentVersion, AllocationStatus.CONFIRMED, group -> {
            Instant now = clock.instant();
            List<Allocation> confirmed = group.allocations().stream().map(a -> a.confirm(reviewer, now)).toList();
            List<Invoice> settled;
            try {
                settled = ReconcileService.settle(confirmed, group.invoices());
            } catch (InvalidInvoiceException refused) {
                return new DecisionResult(Outcome.REFUSED, refused.getMessage());
            }
            List<Allocation> superseded = group.otherProposals().stream()
                    .map(a -> a.reject(reviewer, now, SUPERSEDED))
                    .toList();
            reviews.recordConfirmation(group, confirmed, settled, superseded);
            return DecisionResult.done("Confirmed: " + group.transactionAmount() + " allocated to " + numbers(group) + ".");
        });
    }

    @Override
    public DecisionResult reject(UUID groupId, long expectedPaymentVersion, String reviewer, String reason) {
        String note = reason == null || reason.isBlank() ? "Rejected without a reason" : reason.strip();
        if (note.length() > 500) {
            return new DecisionResult(Outcome.REFUSED, "A reason has at most 500 characters.");
        }
        return decide(groupId, expectedPaymentVersion, AllocationStatus.REJECTED, group -> {
            Instant now = clock.instant();
            reviews.recordRejection(group, group.allocations().stream().map(a -> a.reject(reviewer, now, note)).toList());
            return DecisionResult.done("Rejected: " + numbers(group) + " will not be proposed again for this payment.");
        });
    }

    private DecisionResult decide(UUID groupId, long expectedVersion, AllocationStatus wanted, Decision decision) {
        Optional<ProposalGroup> found = reviews.findGroup(groupId);
        if (found.isEmpty()) {
            return new DecisionResult(Outcome.NOT_FOUND, "This proposal does not exist.");
        }
        ProposalGroup group = found.get();
        Optional<DecisionResult> alreadyDecided = alreadyDecided(group, wanted);
        if (alreadyDecided.isPresent()) {
            return alreadyDecided.get();
        }
        if (group.transactionVersion() != expectedVersion) {
            return DecisionResult.stale();
        }
        try {
            return decision.apply(group);
        } catch (StaleDataException raced) {
            return reviews.findGroup(groupId).flatMap(current -> alreadyDecided(current, wanted))
                    .orElseGet(DecisionResult::stale);
        }
    }

    private static Optional<DecisionResult> alreadyDecided(ProposalGroup group, AllocationStatus wanted) {
        AllocationStatus status = group.allocations().getFirst().status();
        if (status == AllocationStatus.PROPOSED) {
            return Optional.empty();
        }
        if (status == wanted) {
            return Optional.of(new DecisionResult(Outcome.ALREADY_DONE,
                    "This proposal is already " + status.name().toLowerCase(java.util.Locale.ROOT) + "."));
        }
        return Optional.of(DecisionResult.stale());
    }

    private static String numbers(ProposalGroup group) {
        return group.invoices().stream().map(invoice -> invoice.number().value()).sorted()
                .collect(Collectors.joining(", "));
    }

    @FunctionalInterface
    private interface Decision {
        DecisionResult apply(ProposalGroup group);
    }
}
