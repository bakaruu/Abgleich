package dev.abgleich.adapter.in.rest;

import dev.abgleich.adapter.in.rest.ApiJson.ImportedStatementJson;
import dev.abgleich.adapter.in.rest.ApiJson.ReportJson;
import dev.abgleich.adapter.in.rest.ApiJson.UploadResponse;
import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ImportStatementCommand;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.in.StatementProcessed;
import dev.abgleich.application.port.in.StatementReportQuery;
import java.util.UUID;
import java.util.stream.IntStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/statements")
class StatementApiController {

    private final ProcessStatementUseCase processStatement;
    private final StatementReportQuery reports;

    StatementApiController(ProcessStatementUseCase processStatement, StatementReportQuery reports) {
        this.processStatement = processStatement;
        this.reports = reports;
    }

    /** 201 when the file was imported now, 200 when it had been imported before and nothing changed (B21). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<UploadResponse> upload(@RequestPart("file") MultipartFile file) {
        StatementProcessed processed = processStatement.process(
                new ImportStatementCommand(ImportSource.REST, file::getInputStream));
        ImportResult imported = processed.imported();
        UploadResponse body = new UploadResponse(imported.outcome().name(), imported.format().name(),
                IntStream.range(0, imported.statements().size())
                        .mapToObj(i -> ImportedStatementJson.of(imported.statements().get(i),
                                processed.reconciliations().get(i)))
                        .toList());
        HttpStatus status = imported.outcome() == ImportResult.Outcome.IMPORTED ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(body);
    }

    @GetMapping("/{importId}")
    ReportJson report(@PathVariable UUID importId) {
        return reports.report(importId).map(ReportJson::of).orElseThrow(() -> new NotFoundException(
                "No statement import with id " + importId));
    }
}
