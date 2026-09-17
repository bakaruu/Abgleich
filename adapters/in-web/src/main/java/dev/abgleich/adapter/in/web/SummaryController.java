package dev.abgleich.adapter.in.web;

import dev.abgleich.application.reporting.port.in.SummaryQuery;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** The Summary screen: what was settled automatically, what waits for people, and how often they agree. */
@Controller
class SummaryController {

    static final String VIEW = "summary";

    private final SummaryQuery summaries;

    SummaryController(SummaryQuery summaries) {
        this.summaries = summaries;
    }

    @GetMapping("/summary")
    String summary(Model model) {
        model.addAttribute("summary", summaries.summary());
        return VIEW;
    }
}
