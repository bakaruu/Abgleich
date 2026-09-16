package dev.abgleich.adapter.in.rest;

import dev.abgleich.application.port.in.DecisionResult;
import dev.abgleich.application.port.in.ReviewProposalUseCase;
import dev.abgleich.application.port.in.ReviewQueueQuery;
import dev.abgleich.application.port.in.ReviewQueueQuery.ReviewItem;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review queue for integrations. A decision sends the payment version it saw in {@code If-Match};
 * a mismatch answers 412 Precondition Failed and changes nothing (B34). Repeating a decision answers
 * 200 again (B33).
 */
@RestController
class ReviewApiController {

    /** Until API tokens arrive with the public demo (F4), decisions made through the API carry this name. */
    static final String API_REVIEWER = "api client";

    private final ReviewQueueQuery queue;
    private final ReviewProposalUseCase review;

    ReviewApiController(ReviewQueueQuery queue, ReviewProposalUseCase review) {
        this.queue = queue;
        this.review = review;
    }

    @GetMapping("/api/v1/review-queue")
    List<ApiJson.ReviewItemJson> queue(@RequestParam(defaultValue = "100") int limit) {
        List<ReviewItem> items = queue.pending(limit);
        return items.stream().map(ApiJson.ReviewItemJson::of).toList();
    }

    @PostMapping("/api/v1/proposals/{groupId}/confirm")
    ResponseEntity<ApiJson.DecisionJson> confirm(@PathVariable UUID groupId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        return respond(review.confirm(groupId, ETags.version(ifMatch), API_REVIEWER));
    }

    @PostMapping("/api/v1/proposals/{groupId}/reject")
    ResponseEntity<ApiJson.DecisionJson> reject(@PathVariable UUID groupId,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestBody(required = false) ApiJson.RejectRequest request) {
        return respond(review.reject(groupId, ETags.version(ifMatch), API_REVIEWER,
                request == null ? null : request.reason()));
    }

    static ResponseEntity<ApiJson.DecisionJson> respond(DecisionResult result) {
        if (result.outcome() == DecisionResult.Outcome.DONE || result.outcome() == DecisionResult.Outcome.ALREADY_DONE) {
            return ResponseEntity.ok(new ApiJson.DecisionJson(result.outcome().name(), result.message()));
        }
        throw new DecisionRefusedException(result);
    }
}
