package dev.abgleich.adapter.in.web;

import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.application.port.in.ExampleDataUseCase.ExampleLoaded;
import dev.abgleich.application.port.in.StatementReportQuery;
import dev.abgleich.application.port.out.ExampleFile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/** "Load example" and the downloadable example files on the upload screen. */
@Controller
class ExampleController {

    private final ExampleDataUseCase examples;
    private final StatementReportQuery reports;

    ExampleController(ExampleDataUseCase examples, StatementReportQuery reports) {
        this.examples = examples;
        this.reports = reports;
    }

    /** Without a country both examples are loaded; with CH or ES only that country's invoices and statement. */
    @PostMapping("/examples")
    String load(@RequestParam(name = "country", required = false) String country,
            @RequestHeader(name = "HX-Request", required = false) String htmxRequest, Model model) {
        ExampleLoaded loaded;
        if (country == null || country.isBlank()) {
            loaded = examples.load();
        } else if (examples.files().stream().anyMatch(file -> file.country().equals(country))) {
            loaded = examples.load(country);
        } else {
            model.addAttribute("error", "There is no example for that country.");
            return htmxRequest != null ? StatementUploadController.RESULT_FRAGMENT : StatementUploadController.VIEW;
        }
        model.addAttribute("example", loaded);
        model.addAttribute("results", loaded.files().stream()
                .map(file -> UploadView.of(file.processed(), reports, file.file().name()))
                .toList());
        return htmxRequest != null ? StatementUploadController.RESULT_FRAGMENT : StatementUploadController.VIEW;
    }

    /** Only names from the generated list are served, so no path from the request touches the file system. */
    @GetMapping("/examples/files/{name}")
    ResponseEntity<byte[]> download(@PathVariable String name) {
        return examples.file(name)
                .map(file -> ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .header(HttpHeaders.CONTENT_DISPOSITION,
                                ContentDisposition.attachment().filename(file.name()).build().toString())
                        .body(file.content()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
