package dev.abgleich.adapter.in.web;

import dev.abgleich.application.example.ExampleFile;
import dev.abgleich.application.example.port.in.ExampleDataUseCase;
import dev.abgleich.application.reconciliation.port.in.ReviewQueueQuery;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Model attributes every page of the web UI shows: the review badge, the example files and the demo banner. */
@ControllerAdvice(basePackageClasses = WebModelAdvice.class)
class WebModelAdvice {

    private final ExampleDataUseCase examples;
    private final ReviewQueueQuery reviewQueue;
    private final boolean demoMode;
    private final String repositoryUrl;
    private final String grafanaUrl;

    WebModelAdvice(ExampleDataUseCase examples, ReviewQueueQuery reviewQueue,
            @Value("${abgleich.demo.enabled:false}") boolean demoMode,
            @Value("${abgleich.demo.repository-url:}") String repositoryUrl,
            @Value("${abgleich.demo.grafana-url:}") String grafanaUrl) {
        this.examples = examples;
        this.reviewQueue = reviewQueue;
        this.demoMode = demoMode;
        this.repositoryUrl = repositoryUrl.isBlank() ? null : repositoryUrl;
        this.grafanaUrl = grafanaUrl.isBlank() ? null : grafanaUrl;
    }

    @ModelAttribute("exampleFiles")
    List<ExampleFile> exampleFiles() {
        return examples.files();
    }

    @ModelAttribute("pendingReview")
    long pendingReview() {
        return reviewQueue.pendingCount();
    }

    @ModelAttribute("demoMode")
    boolean demoMode() {
        return demoMode;
    }

    @ModelAttribute("repositoryUrl")
    String repositoryUrl() {
        return repositoryUrl;
    }

    @ModelAttribute("grafanaUrl")
    String grafanaUrl() {
        return grafanaUrl;
    }
}
