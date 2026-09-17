package dev.abgleich.adapter.in.web;

import dev.abgleich.application.DecisionResult;
import org.springframework.http.HttpStatus;

/** How a decision result is shown: its HTTP status for htmx swaps and its alert style. */
final class Decisions {

    /** Until sign-in arrives with the public demo (F4), decisions made in the browser are attributed to this name. */
    static final String WEB_REVIEWER = "web reviewer";

    private Decisions() {
    }

    static HttpStatus status(DecisionResult result) {
        return switch (result.outcome()) {
            case DONE, ALREADY_DONE -> HttpStatus.OK;
            case STALE -> HttpStatus.CONFLICT;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case REFUSED -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
    }

    static String alertClass(DecisionResult result) {
        return switch (result.outcome()) {
            case DONE -> "alert-ok";
            case ALREADY_DONE, STALE -> "alert-known";
            case NOT_FOUND, REFUSED -> "alert-error";
        };
    }
}
