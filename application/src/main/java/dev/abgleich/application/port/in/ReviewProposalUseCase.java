package dev.abgleich.application.port.in;

import java.util.UUID;

/**
 * A person confirms or rejects a proposal. A proposal is a group of allocations decided together.
 * The caller sends the payment version it showed; if the payment changed since, nothing happens (B34).
 */
public interface ReviewProposalUseCase {

    DecisionResult confirm(UUID proposalGroupId, long expectedPaymentVersion, String reviewer);

    DecisionResult reject(UUID proposalGroupId, long expectedPaymentVersion, String reviewer, String reason);
}
