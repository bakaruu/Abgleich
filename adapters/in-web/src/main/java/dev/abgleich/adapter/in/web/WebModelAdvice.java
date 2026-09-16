package dev.abgleich.adapter.in.web;

import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.application.port.in.ReviewQueueQuery;
import dev.abgleich.application.port.out.ExampleFile;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Model attributes every page of the web UI shows: the review badge and the example files. */
@ControllerAdvice(basePackageClasses = WebModelAdvice.class)
class WebModelAdvice {

    private final ExampleDataUseCase examples;
    private final ReviewQueueQuery reviewQueue;

    WebModelAdvice(ExampleDataUseCase examples, ReviewQueueQuery reviewQueue) {
        this.examples = examples;
        this.reviewQueue = reviewQueue;
    }

    @ModelAttribute("exampleFiles")
    List<ExampleFile> exampleFiles() {
        return examples.files();
    }

    @ModelAttribute("pendingReview")
    long pendingReview() {
        return reviewQueue.pendingCount();
    }
}
