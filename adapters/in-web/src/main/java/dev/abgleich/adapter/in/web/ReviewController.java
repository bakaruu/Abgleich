package dev.abgleich.adapter.in.web;

import dev.abgleich.application.DecisionResult;
import dev.abgleich.application.reconciliation.port.in.ReviewProposalUseCase;
import dev.abgleich.application.reconciliation.port.in.ReviewQueueQuery;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The review queue. Each decision sends the payment version the page showed (B34); the button is
 * disabled while the request runs and a repeated decision is harmless (B33).
 */
@Controller
class ReviewController {

    static final int PAGE_SIZE = 100;

    private final ReviewQueueQuery queue;
    private final ReviewProposalUseCase review;

    ReviewController(ReviewQueueQuery queue, ReviewProposalUseCase review) {
        this.queue = queue;
        this.review = review;
    }

    @GetMapping("/review")
    String page(Model model) {
        model.addAttribute("items", queue.pending(PAGE_SIZE));
        return "review";
    }

    @PostMapping("/review/proposals/{groupId}/confirm")
    String confirm(@PathVariable UUID groupId, @RequestParam long version,
            @RequestHeader(name = "HX-Request", required = false) String htmxRequest,
            Model model, HttpServletResponse response, RedirectAttributes redirect) {
        return respond(review.confirm(groupId, version, Decisions.WEB_REVIEWER), htmxRequest, model, response, redirect);
    }

    @PostMapping("/review/proposals/{groupId}/reject")
    String reject(@PathVariable UUID groupId, @RequestParam long version,
            @RequestParam(required = false) String reason,
            @RequestHeader(name = "HX-Request", required = false) String htmxRequest,
            Model model, HttpServletResponse response, RedirectAttributes redirect) {
        return respond(review.reject(groupId, version, Decisions.WEB_REVIEWER, reason), htmxRequest, model, response,
                redirect);
    }

    private static String respond(DecisionResult result, String htmxRequest, Model model, HttpServletResponse response,
            RedirectAttributes redirect) {
        if (htmxRequest == null) {
            redirect.addFlashAttribute("decision", result);
            redirect.addFlashAttribute("decisionClass", Decisions.alertClass(result));
            return "redirect:/review";
        }
        response.setStatus(Decisions.status(result).value());
        model.addAttribute("decisionOnly", true);
        model.addAttribute("decision", result);
        model.addAttribute("decisionClass", Decisions.alertClass(result));
        return "review :: decision";
    }
}
