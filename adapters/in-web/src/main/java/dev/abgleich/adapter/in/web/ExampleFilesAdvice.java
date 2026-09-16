package dev.abgleich.adapter.in.web;

import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.application.port.out.ExampleFile;
import java.util.List;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** Every page of the web UI lists the downloadable example files next to the upload form. */
@ControllerAdvice(basePackageClasses = ExampleFilesAdvice.class)
class ExampleFilesAdvice {

    private final ExampleDataUseCase examples;

    ExampleFilesAdvice(ExampleDataUseCase examples) {
        this.examples = examples;
    }

    @ModelAttribute("exampleFiles")
    List<ExampleFile> exampleFiles() {
        return examples.files();
    }
}
